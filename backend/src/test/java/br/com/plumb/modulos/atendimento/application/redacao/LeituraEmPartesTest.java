package br.com.plumb.modulos.atendimento.application.redacao;

import br.com.plumb.modulos.atendimento.ModeloDeMentira;
import br.com.plumb.modulos.atendimento.domain.ia.FalhaDoModelo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** O atendimento longo lido em partes pela IA local, para caber no que ela lê de uma vez. */
class LeituraEmPartesTest {

    private static List<String> itens(int quantos, int tamanho) {
        List<String> itens = new ArrayList<>();
        for (int i = 1; i <= quantos; i++) {
            itens.add("[M" + i + "] 01/10 09:00 · mensagem do cliente:\n" + "x".repeat(tamanho));
        }
        return itens;
    }

    @Test
    void agrupaSemSepararOsItensECortaSoOQueSozinhoPassaDoLimite() {
        List<String> partes = LeituraEmPartes.agrupar(List.of("a".repeat(40), "b".repeat(40), "c".repeat(40)), 100);
        assertThat(partes).containsExactly("a".repeat(40) + "\n\n" + "b".repeat(40), "c".repeat(40));

        String longo = "linha um\n" + "z".repeat(250) + "\nlinha três";
        List<String> cortado = LeituraEmPartes.agrupar(List.of(longo), 100);
        assertThat(cortado).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(100));
        assertThat(String.join("", cortado).replace("\n", "")).isEqualTo(longo.replace("\n", ""));
    }

    @Test
    void cadaParteCabeNoContextoEAsNotasLevamOsIdentificadores() {
        ModeloDeMentira modelo = new ModeloDeMentira().local(true);
        AtomicInteger parte = new AtomicInteger();
        modelo.padrao(p -> "- [M" + parte.incrementAndGet() + "] nota da parte");
        LeituraEmPartes leitura = new LeituraEmPartes(modelo, "gemma4:12b", 8192);

        LeituraEmPartes.Lido lido = leitura.ler("local", "Atendimento do chamado 123.", itens(60, 1000), 4000);

        assertThat(lido.partes()).isGreaterThan(1).isEqualTo(modelo.geracoes().size());
        assertThat(lido.notas()).contains("[M1] nota da parte").contains("[M" + lido.partes() + "] nota da parte");
        assertThat(modelo.geracoes()).allSatisfy(g -> {
            assertThat(g.pedido().instrucaoDoSistema()).isEqualTo(LeituraEmPartes.SISTEMA_DAS_NOTAS);
            assertThat(g.pedido().instrucao()).startsWith("Atendimento do chamado 123.").contains("Parte ");
            int tokens = LeituraEmPartes.tokens(g.pedido().instrucao()) + LeituraEmPartes.tokens(g.pedido().instrucaoDoSistema())
                    + g.pedido().maxTokensSaida();
            assertThat(tokens).as("cabe no contexto").isLessThanOrEqualTo(8192);
        });
    }

    @Test
    void parteQueNaoCoubeEDivididaAoMeio() {
        ModeloDeMentira modelo = new ModeloDeMentira().local(true);
        modelo.falhar(new FalhaDoModelo(FalhaDoModelo.Tipo.MATERIAL_GRANDE, "O material passou do tanto que a IA local lê."));
        modelo.padrao(p -> "- [M1] nota");
        LeituraEmPartes leitura = new LeituraEmPartes(modelo, "gemma4:12b", 8192);

        LeituraEmPartes.Lido lido = leitura.ler("local", "Atendimento.", itens(10, 1000), 4000);

        assertThat(lido.partes()).isEqualTo(2);
        assertThat(modelo.geracoes()).hasSize(3);
        assertThat(modelo.geracoes().get(1).pedido().instrucao().length())
                .isLessThan(modelo.geracoes().get(0).pedido().instrucao().length());
    }

    @Test
    void notasQueNuncaCabemViramUmaFalhaClara() {
        ModeloDeMentira modelo = new ModeloDeMentira().local(true);
        modelo.padrao(p -> "y".repeat(5000)); // as notas não encurtam nunca
        LeituraEmPartes leitura = new LeituraEmPartes(modelo, "gemma4:12b", 8192);

        assertThatThrownBy(() -> leitura.ler("local", "Atendimento.", itens(60, 1000), 1500))
                .isInstanceOfSatisfying(FalhaDoModelo.class, f -> {
                    assertThat(f.tipo()).isEqualTo(FalhaDoModelo.Tipo.MATERIAL_GRANDE);
                    assertThat(f.getMessage()).startsWith("Mesmo lido em partes");
                });
    }
}
