package br.com.plumb.modulos.clientes.infrastructure.cripto;

import br.com.plumb.domain.exception.ConflitoException;
import br.com.plumb.domain.exception.RegraNegocioException;
import br.com.plumb.modulos.clientes.application.cofre.ChavesDoCofre;
import br.com.plumb.modulos.clientes.application.cofre.RegistroDoCofre;
import br.com.plumb.modulos.clientes.infrastructure.config.ConfiguracaoDosClientes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChaveDoCofreEmArquivoTest {

    @TempDir
    Path pasta;

    private final RegistroDoCofre registro = mock(RegistroDoCofre.class);

    private ChaveDoCofreEmArquivo cofre() {
        return new ChaveDoCofreEmArquivo(new ConfiguracaoDosClientes(pasta, null), registro);
    }

    @Test
    @DisplayName("Nasce no primeiro uso; cifra e decifra; outro dono ou dado alterado não abre")
    void cifraEDecifra() throws Exception {
        ChaveDoCofreEmArquivo cofre = cofre();
        assertThat(cofre.situacao()).isEqualTo(ChavesDoCofre.Situacao.NOVA);
        UUID dono = UUID.randomUUID();
        ChavesDoCofre.Cifrado cifrado = cofre.cifrar("pa$$w0rd do cliente", dono);

        assertThat(cofre.situacao()).isEqualTo(ChavesDoCofre.Situacao.PRONTA);
        assertThat(Files.size(pasta.resolve("chave-do-cofre"))).isEqualTo(32);
        assertThat(new String(cifrado.dados())).doesNotContain("pa$$w0rd");
        assertThat(cofre.decifrar(cifrado, dono)).isEqualTo("pa$$w0rd do cliente");
        // vetor novo a cada cifra
        assertThat(cofre.cifrar("pa$$w0rd do cliente", dono).vetor()).isNotEqualTo(cifrado.vetor());

        assertThatThrownBy(() -> cofre.decifrar(cifrado, UUID.randomUUID()))
                .isInstanceOf(RegraNegocioException.class).hasFieldOrPropertyWithValue("codigo", "COFRE_NAO_ABRE");
        byte[] alterado = cifrado.dados().clone();
        alterado[0] ^= 1;
        assertThatThrownBy(() -> cofre.decifrar(new ChavesDoCofre.Cifrado(alterado, cifrado.vetor()), dono))
                .isInstanceOf(RegraNegocioException.class).hasFieldOrPropertyWithValue("codigo", "COFRE_NAO_ABRE");
        // a mesma chave, lida do arquivo por outra instância
        assertThat(cofre().decifrar(cifrado, dono)).isEqualTo("pa$$w0rd do cliente");
    }

    @Test
    @DisplayName("Sem o arquivo e com dados cifrados no banco: trancado, nunca cria chave nova por cima")
    void naoCriaChaveSobreDados() throws Exception {
        UUID dono = UUID.randomUUID();
        ChavesDoCofre.Cifrado cifrado = cofre().cifrar("segredo", dono);
        Files.delete(pasta.resolve("chave-do-cofre"));
        when(registro.haDadosCifrados()).thenReturn(true);

        ChaveDoCofreEmArquivo depois = cofre();
        assertThat(depois.situacao()).isEqualTo(ChavesDoCofre.Situacao.TRANCADA);
        assertThatThrownBy(() -> depois.cifrar("outro", dono))
                .isInstanceOf(RegraNegocioException.class).hasFieldOrPropertyWithValue("codigo", "COFRE_TRANCADO");
        assertThatThrownBy(() -> depois.decifrar(cifrado, dono))
                .isInstanceOf(RegraNegocioException.class).hasFieldOrPropertyWithValue("codigo", "COFRE_TRANCADO");
        assertThat(pasta.resolve("chave-do-cofre")).doesNotExist();
    }

    @Test
    @DisplayName("Frase de recuperação: a cópia embrulhada recria a chave; frase errada não; sem a frase o backup não abre")
    void fraseDeRecuperacao() throws Exception {
        UUID dono = UUID.randomUUID();
        ChaveDoCofreEmArquivo cofre = cofre();
        ChavesDoCofre.Cifrado cifrado = cofre.cifrar("senha do servidor", dono);
        assertThat(cofre.temRecuperacao()).isFalse();
        cofre.definirFraseDeRecuperacao("cavalo bateria grampo correto");
        assertThat(cofre.temRecuperacao()).isTrue();

        String copia = Files.readString(pasta.resolve("chave-do-cofre.recuperacao"));
        assertThat(copia).contains("\"iteracoes\" : 600000").doesNotContain(
                java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(pasta.resolve("chave-do-cofre"))));

        // computador novo: só a cópia embrulhada (a que vai no backup)
        Files.delete(pasta.resolve("chave-do-cofre"));
        when(registro.haDadosCifrados()).thenReturn(true);
        ChaveDoCofreEmArquivo novo = cofre();
        assertThat(novo.situacao()).isEqualTo(ChavesDoCofre.Situacao.TRANCADA);
        assertThatThrownBy(() -> novo.restaurarComFrase("cavalo bateria grampo errado"))
                .isInstanceOf(RegraNegocioException.class).hasFieldOrPropertyWithValue("codigo", "FRASE_ERRADA");
        assertThat(novo.situacao()).isEqualTo(ChavesDoCofre.Situacao.TRANCADA);

        novo.restaurarComFrase("cavalo bateria grampo correto");
        assertThat(novo.situacao()).isEqualTo(ChavesDoCofre.Situacao.PRONTA);
        assertThat(novo.decifrar(cifrado, dono)).isEqualTo("senha do servidor");
        assertThatThrownBy(() -> novo.restaurarComFrase("cavalo bateria grampo correto"))
                .isInstanceOf(ConflitoException.class);
    }

    @Test
    @DisplayName("Só a cópia embrulhada e nenhum dado no banco: continua trancado (não cria chave nova)")
    void copiaSemChaveTranca() throws Exception {
        ChaveDoCofreEmArquivo cofre = cofre();
        cofre.cifrar("x", UUID.randomUUID());
        cofre.definirFraseDeRecuperacao("uma frase longa o bastante");
        Files.delete(pasta.resolve("chave-do-cofre"));
        assertThat(cofre().situacao()).isEqualTo(ChavesDoCofre.Situacao.TRANCADA);
    }
}
