package br.com.plumb.modulos.atendimento.application.redacao;

import br.com.plumb.modulos.atendimento.application.ia.ArquivosDePrompt;
import br.com.plumb.modulos.atendimento.domain.ia.FalhaDoModelo;
import br.com.plumb.modulos.atendimento.domain.ia.ModeloDeIA;
import br.com.plumb.modulos.atendimento.domain.ia.PedidoAoModelo;
import br.com.plumb.modulos.atendimento.domain.ia.RespostaDoModelo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Um atendimento longo demais para a IA local ler de uma vez (o contexto do modelo): os itens da linha do tempo vão em
 * partes que cabem, a IA escreve as notas de cada parte (com os identificadores dos itens), e a redação é feita a
 * partir das notas. Se as notas juntas ainda não couberem, elas são lidas em partes de novo (no máximo
 * {@link #NIVEIS} vezes).
 *
 * <p>O tamanho em tokens é estimado pelos caracteres ({@link #CARACTERES_POR_TOKEN}, para menos: em português o
 * modelo usa perto de 4 caracteres por token). Se mesmo assim uma parte não couber, ela é dividida ao meio.
 */
final class LeituraEmPartes {

    private static final Logger log = LoggerFactory.getLogger(LeituraEmPartes.class);

    /** Para menos: assim a estimativa nunca fica abaixo do que o modelo conta. */
    static final int CARACTERES_POR_TOKEN = 3;
    /** As notas de uma parte (tokens). */
    static final int SAIDA_DAS_NOTAS = 2048;
    /** O que vai em volta do material de cada pedido (cabeçalho, número da parte) e a folga da estimativa. */
    static final int FOLGA = 768;
    /** Quantas vezes as notas podem ser lidas em partes de novo. */
    static final int NIVEIS = 3;
    /** Uma parte menor que isto (caracteres) não é mais dividida: o material não cabe nem assim. */
    static final int MENOR_PARTE = 1500;

    static final String SISTEMA_DAS_NOTAS = ArquivosDePrompt.prompt("notas", 1);

    /** O material já em notas, quantas partes foram lidas e os tokens gastos. */
    record Lido(String notas, int partes, int tokensEntrada, int tokensSaida) {
    }

    private final ModeloDeIA modelo;
    private final String nomeDoModelo;
    private final int contexto;

    LeituraEmPartes(ModeloDeIA modelo, String nomeDoModelo, int contexto) {
        this.modelo = modelo;
        this.nomeDoModelo = nomeDoModelo;
        this.contexto = contexto;
    }

    static int tokens(String texto) {
        return texto == null ? 0 : texto.length() / CARACTERES_POR_TOKEN + 1;
    }

    /** Quantos caracteres de material cabem num pedido de notas. */
    int caracteresPorParte() {
        int tokens = contexto - SAIDA_DAS_NOTAS - tokens(SISTEMA_DAS_NOTAS) - FOLGA;
        return Math.max(MENOR_PARTE, tokens * CARACTERES_POR_TOKEN);
    }

    /**
     * As notas dos itens, até caberem em {@code orcamento} tokens.
     *
     * @throws FalhaDoModelo MATERIAL_GRANDE quando nem dividindo dá para ler; as outras falhas do modelo seguem
     */
    Lido ler(String chave, String cabecalho, List<String> itens, int orcamento) {
        List<String> blocos = itens;
        int partes = 0;
        int entrada = 0;
        int saida = 0;
        for (int nivel = 1; ; nivel++) {
            List<String> pedacos = agrupar(blocos, caracteresPorParte());
            List<String> notas = new ArrayList<>();
            for (int i = 0; i < pedacos.size(); i++) {
                Notas lidas = notas(chave, cabecalho, pedacos.get(i), i + 1, pedacos.size(), nivel);
                notas.addAll(lidas.textos());
                partes += lidas.partes();
                entrada += lidas.tokensEntrada();
                saida += lidas.tokensSaida();
            }
            String juntas = String.join("\n\n", notas);
            log.info("IA local: atendimento longo lido em {} partes (nível {}): {} → {} caracteres", pedacos.size(), nivel,
                    blocos.stream().mapToInt(String::length).sum(), juntas.length());
            if (tokens(juntas) <= orcamento) {
                return new Lido(juntas, partes, entrada, saida);
            }
            if (nivel >= NIVEIS || notas.size() <= 1) {
                throw new FalhaDoModelo(FalhaDoModelo.Tipo.MATERIAL_GRANDE,
                        "Mesmo lido em partes, o atendimento ficou maior do que a IA local lê de uma vez.", null, 0,
                        "notas com " + juntas.length() + " caracteres para um orçamento de " + orcamento + " tokens");
            }
            blocos = notas;
        }
    }

    private record Notas(List<String> textos, int partes, int tokensEntrada, int tokensSaida) {
    }

    /** As notas de uma parte; se o modelo disser que ela não coube, divide ao meio e lê as duas metades. */
    private Notas notas(String chave, String cabecalho, String parte, int numero, int total, int nivel) {
        try {
            String instrucao = cabecalho + "\n\n" + (nivel == 1
                    ? "Parte " + numero + " de " + total + " do material, em ordem (cada item com o identificador entre colchetes):"
                    : "Parte " + numero + " de " + total + " das notas já escritas deste atendimento (junte e encurte, sem perder "
                    + "os fatos e os identificadores):")
                    + "\n\n" + parte + "\n\n---\n\nEscreva as notas desta parte.";
            PedidoAoModelo pedido = new PedidoAoModelo(nomeDoModelo, SISTEMA_DAS_NOTAS, instrucao, PedidoAoModelo.SEM_ESQUEMA,
                    null, false, null, null, SAIDA_DAS_NOTAS);
            RespostaDoModelo resposta = modelo.gerar(chave, pedido);
            String texto = resposta.texto() == null ? "" : resposta.texto().strip();
            if (texto.isEmpty()) {
                throw new FalhaDoModelo(FalhaDoModelo.Tipo.RESPOSTA_INVALIDA, "a IA local não escreveu as notas de uma parte");
            }
            return new Notas(List.of(texto), 1, resposta.tokensEntrada(), resposta.tokensSaida());
        } catch (FalhaDoModelo falha) {
            if (falha.tipo() != FalhaDoModelo.Tipo.MATERIAL_GRANDE || parte.length() / 2 < MENOR_PARTE) {
                throw falha;
            }
            log.info("IA local: a parte {} de {} não coube ({} caracteres): dividida ao meio", numero, total, parte.length());
            List<String> metades = agrupar(List.of(parte), parte.length() / 2 + 1);
            List<String> textos = new ArrayList<>();
            int partes = 0;
            int entrada = 0;
            int saida = 0;
            for (String metade : metades) {
                Notas lidas = notas(chave, cabecalho, metade, numero, total, nivel);
                textos.addAll(lidas.textos());
                partes += lidas.partes();
                entrada += lidas.tokensEntrada();
                saida += lidas.tokensSaida();
            }
            return new Notas(textos, partes, entrada, saida);
        }
    }

    /**
     * Os blocos juntos em partes de até {@code limite} caracteres, sem separar um bloco, a não ser o que sozinho passa
     * do limite (esse é cortado nas quebras de linha, ou no meio de uma linha longa demais).
     */
    static List<String> agrupar(List<String> blocos, int limite) {
        List<String> partes = new ArrayList<>();
        StringBuilder atual = new StringBuilder();
        for (String bloco : blocos) {
            for (String pedaco : cortar(bloco, limite)) {
                if (atual.length() > 0 && atual.length() + 2 + pedaco.length() > limite) {
                    partes.add(atual.toString());
                    atual.setLength(0);
                }
                if (atual.length() > 0) {
                    atual.append("\n\n");
                }
                atual.append(pedaco);
            }
        }
        if (atual.length() > 0) {
            partes.add(atual.toString());
        }
        return partes;
    }

    private static List<String> cortar(String bloco, int limite) {
        if (bloco.length() <= limite) {
            return List.of(bloco);
        }
        List<String> pedacos = new ArrayList<>();
        StringBuilder atual = new StringBuilder();
        for (String linha : bloco.split("\n", -1)) {
            while (linha.length() > limite) {
                if (atual.length() > 0) {
                    pedacos.add(atual.toString());
                    atual.setLength(0);
                }
                pedacos.add(linha.substring(0, limite));
                linha = linha.substring(limite);
            }
            if (atual.length() > 0 && atual.length() + 1 + linha.length() > limite) {
                pedacos.add(atual.toString());
                atual.setLength(0);
            }
            if (atual.length() > 0) {
                atual.append('\n');
            }
            atual.append(linha);
        }
        if (!atual.isEmpty()) {
            pedacos.add(atual.toString());
        }
        return pedacos;
    }
}
