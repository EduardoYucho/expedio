package br.com.plumb.modulos.atendimento.infrastructure.local;

import br.com.plumb.modulos.atendimento.domain.ia.FalhaDoModelo;
import br.com.plumb.modulos.atendimento.domain.ia.FalhaDoModelo.Tipo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * A API do Ollama neste computador ({@code /api/chat}, {@code /api/embed}, {@code /api/ps}). Do conteúdo, nada vai para
 * o log; do Ollama, só o código HTTP e o motivo.
 */
class ClienteOllama {

    private static final Logger log = LoggerFactory.getLogger(ClienteOllama.class);

    private final URI base;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Duration tempoLimite;

    ClienteOllama(URI base, ObjectMapper json, Duration tempoConexao, Duration tempoLimite) {
        this.base = base;
        this.json = json;
        this.tempoLimite = tempoLimite;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(tempoConexao)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Uma conversa ({@code stream: false}). Pede sem raciocínio ({@code think: false}): sem isso o modelo pode passar
     * minutos "pensando" antes de responder; se o modelo não aceitar o campo, pede de novo sem ele.
     */
    JsonNode conversar(ObjectNode corpo) {
        corpo.put("stream", false);
        corpo.put("think", false);
        HttpResponse<String> resposta = postar("/api/chat", corpo, tempoLimite);
        if (resposta.statusCode() == 400 && resposta.body() != null && resposta.body().contains("think")) {
            corpo.remove("think");
            resposta = postar("/api/chat", corpo, tempoLimite);
        }
        return lido(resposta, "conversa");
    }

    /** Os vetores de um texto ({@code /api/embed}). */
    JsonNode vetores(String modelo, String texto, Duration manterCarregado) {
        ObjectNode corpo = json.createObjectNode();
        corpo.put("model", modelo);
        corpo.put("input", texto);
        corpo.put("keep_alive", segundos(manterCarregado));
        return lido(postar("/api/embed", corpo, tempoLimite), "vetor");
    }

    /**
     * Algum modelo está carregado agora ({@code /api/ps}). Não dá para usar o {@code size_vram} daqui como a memória
     * que ele ocupa: com o gemma4 o Ollama informa só uma parte.
     */
    boolean algumCarregado() {
        try {
            HttpRequest pedido = HttpRequest.newBuilder(base.resolve("/api/ps")).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());
            return resposta.statusCode() == 200 && !json.readTree(resposta.body()).path("models").isEmpty();
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Tira o modelo da placa de vídeo agora (um jogo abriu); não falha. */
    void descarregar(String modelo) {
        try {
            ObjectNode corpo = json.createObjectNode();
            corpo.put("model", modelo);
            corpo.put("keep_alive", 0);
            postar("/api/generate", corpo, Duration.ofSeconds(30));
            log.info("IA local: o modelo {} saiu da placa de vídeo (ela está sendo usada por outro programa)", modelo);
        } catch (FalhaDoModelo e) {
            log.debug("IA local: não foi possível tirar o modelo da placa ({})", e.getMessage());
        }
    }

    static long segundos(Duration duracao) {
        return duracao == null ? 120 : Math.max(0, duracao.toSeconds());
    }

    private HttpResponse<String> postar(String caminho, ObjectNode corpo, Duration tempo) {
        HttpRequest pedido;
        try {
            pedido = HttpRequest.newBuilder(base.resolve(caminho))
                    .header("Content-Type", "application/json")
                    .timeout(tempo)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo)))
                    .build();
        } catch (IOException e) {
            throw new FalhaDoModelo(Tipo.PEDIDO_RECUSADO, "Não foi possível montar o pedido à IA local.");
        }
        try {
            return http.send(pedido, HttpResponse.BodyHandlers.ofString());
        } catch (HttpConnectTimeoutException | ConnectException e) {
            throw new FalhaDoModelo(Tipo.IA_LOCAL_PARADA, "O Ollama não está respondendo em " + base + ".");
        } catch (HttpTimeoutException e) {
            throw new FalhaDoModelo(Tipo.INDISPONIVEL, "a IA local demorou mais que " + tempo.toMinutes() + " min");
        } catch (IOException e) {
            String motivo = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (motivo.toLowerCase(java.util.Locale.ROOT).contains("connection refused")) {
                throw new FalhaDoModelo(Tipo.IA_LOCAL_PARADA, "O Ollama não está respondendo em " + base + ".", null, 0,
                        motivo);
            }
            throw new FalhaDoModelo(Tipo.INDISPONIVEL, "a conexão com a IA local caiu", null, 0,
                    e.getClass().getSimpleName() + ": " + motivo);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FalhaDoModelo(Tipo.INDISPONIVEL, "o pedido à IA local foi interrompido");
        }
    }

    private JsonNode lido(HttpResponse<String> resposta, String oQue) {
        int status = resposta.statusCode();
        String corpo = resposta.body() == null ? "" : resposta.body();
        if (status == 200) {
            try {
                return json.readTree(corpo);
            } catch (IOException e) {
                throw new FalhaDoModelo(Tipo.RESPOSTA_INVALIDA, "a resposta da IA local não é JSON");
            }
        }
        String erro = erroDe(corpo);
        log.warn("IA local: {} respondeu HTTP {} ({})", oQue, status, erro);
        throw falhaDe(status, erro);
    }

    /**
     * O erro do Ollama (em inglês) traduzido no motivo para a pessoa; o texto original fica só no detalhe (log).
     */
    static FalhaDoModelo falhaDe(int status, String erro) {
        String e = erro == null ? "" : erro.toLowerCase(java.util.Locale.ROOT);
        if (e.contains("context length") || e.contains("context window") || e.contains("prompt is too long")
                || e.contains("exceeds the context")) {
            return new FalhaDoModelo(Tipo.MATERIAL_GRANDE, "O material passou do tanto que a IA local lê de uma vez.",
                    null, status, erro);
        }
        if (status == 404 || e.contains("not found") && e.contains("model")) {
            return new FalhaDoModelo(Tipo.MODELO_INEXISTENTE, "O modelo da IA local não está instalado no Ollama.",
                    null, status, erro);
        }
        if (e.contains("memory") || e.contains("out of memory") || e.contains("cuda") || e.contains("vram")) {
            return new FalhaDoModelo(Tipo.INDISPONIVEL, "a IA local ficou sem memória na placa de vídeo para responder",
                    null, status, erro);
        }
        if (status == 400) {
            return new FalhaDoModelo(Tipo.PEDIDO_RECUSADO, "A IA local não aceitou o pedido.", null, status, erro);
        }
        return new FalhaDoModelo(Tipo.INDISPONIVEL, "a IA local falhou ao responder", null, status, erro);
    }

    private String erroDe(String corpo) {
        try {
            JsonNode lido = json.readTree(corpo);
            String erro = lido == null ? "" : lido.path("error").asText("");
            return erro.isBlank() ? "sem detalhe" : (erro.length() > 200 ? erro.substring(0, 200) : erro);
        } catch (IOException e) {
            return "sem detalhe";
        }
    }
}
