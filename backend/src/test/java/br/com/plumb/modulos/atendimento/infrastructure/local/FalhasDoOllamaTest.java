package br.com.plumb.modulos.atendimento.infrastructure.local;

import br.com.plumb.modulos.atendimento.domain.ia.FalhaDoModelo;
import br.com.plumb.modulos.atendimento.domain.ia.FalhaDoModelo.Tipo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** O erro do Ollama (em inglês) vira um motivo em português; o texto original fica só no detalhe, para o log. */
class FalhasDoOllamaTest {

    private static final String CONTEXTO = "the prompt is longer than the context length currently available to the "
            + "model; shorten the prompt, adjust the context length in settings, or use a model with a longer context length";

    @Test
    void materialMaiorQueOContexto() {
        FalhaDoModelo falha = ClienteOllama.falhaDe(400, CONTEXTO);
        assertThat(falha.tipo()).isEqualTo(Tipo.MATERIAL_GRANDE);
        assertThat(falha.getMessage()).isEqualTo("O material passou do tanto que a IA local lê de uma vez.");
        assertThat(falha.detalhe()).isEqualTo(CONTEXTO);
        assertThat(falha.paraOLog()).contains("HTTP 400").contains(CONTEXTO);
    }

    @Test
    void osOutrosErrosTambemViramMotivo() {
        assertThat(ClienteOllama.falhaDe(404, "model \"gemma9\" not found, try pulling it first"))
                .satisfies(f -> assertThat(f.tipo()).isEqualTo(Tipo.MODELO_INEXISTENTE))
                .satisfies(f -> assertThat(f.getMessage()).doesNotContain("pulling"));
        assertThat(ClienteOllama.falhaDe(500, "llama runner process has terminated: cudaMalloc failed: out of memory"))
                .satisfies(f -> assertThat(f.tipo()).isEqualTo(Tipo.INDISPONIVEL))
                .satisfies(f -> assertThat(f.getMessage()).contains("sem memória na placa de vídeo"));
        assertThat(ClienteOllama.falhaDe(400, "invalid options: num_ctxx"))
                .satisfies(f -> assertThat(f.tipo()).isEqualTo(Tipo.PEDIDO_RECUSADO))
                .satisfies(f -> assertThat(f.getMessage()).isEqualTo("A IA local não aceitou o pedido."));
        assertThat(ClienteOllama.falhaDe(502, "sem detalhe"))
                .satisfies(f -> assertThat(f.getMessage()).isEqualTo("a IA local falhou ao responder"));
    }
}
