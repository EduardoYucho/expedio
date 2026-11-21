package br.com.expedio.modulos.clientes.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TotpTest {

    /** O segredo dos vetores de teste do RFC 6238 (HMAC-SHA1). */
    private static final byte[] SEGREDO = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    @DisplayName("Os vetores do RFC 6238 (SHA1), nos 6 últimos dígitos")
    void vetoresDoRfc() {
        assertThat(Totp.codigo(SEGREDO, Totp.passo(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(Totp.codigo(SEGREDO, Totp.passo(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804");
        assertThat(Totp.codigo(SEGREDO, Totp.passo(Instant.ofEpochSecond(1111111111)))).isEqualTo("050471");
        assertThat(Totp.codigo(SEGREDO, Totp.passo(Instant.ofEpochSecond(1234567890)))).isEqualTo("005924");
        assertThat(Totp.codigo(SEGREDO, Totp.passo(Instant.ofEpochSecond(2000000000)))).isEqualTo("279037");
    }

    @Test
    @DisplayName("Aceita o passo atual e um para cada lado; fora disso, formato errado ou passo já usado, recusa")
    void conferir() {
        Instant agora = Instant.ofEpochSecond(1234567890);
        long passo = Totp.passo(agora);
        assertThat(Totp.conferir(SEGREDO, "005924", agora, -1)).isEqualTo(passo);
        assertThat(Totp.conferir(SEGREDO, "005 924", agora, -1)).isEqualTo(passo);
        assertThat(Totp.conferir(SEGREDO, Totp.codigo(SEGREDO, passo - 1), agora, -1)).isEqualTo(passo - 1);
        assertThat(Totp.conferir(SEGREDO, Totp.codigo(SEGREDO, passo + 1), agora, -1)).isEqualTo(passo + 1);
        assertThat(Totp.conferir(SEGREDO, Totp.codigo(SEGREDO, passo - 2), agora, -1)).isEqualTo(-1);
        assertThat(Totp.conferir(SEGREDO, Totp.codigo(SEGREDO, passo + 2), agora, -1)).isEqualTo(-1);
        // o mesmo código não vale duas vezes
        assertThat(Totp.conferir(SEGREDO, "005924", agora, passo)).isEqualTo(-1);
        assertThat(Totp.conferir(SEGREDO, "000000", agora, -1)).isEqualTo(-1);
        assertThat(Totp.conferir(SEGREDO, "12345", agora, -1)).isEqualTo(-1);
        assertThat(Totp.conferir(SEGREDO, "abcdef", agora, -1)).isEqualTo(-1);
        assertThat(Totp.conferir(SEGREDO, null, agora, -1)).isEqualTo(-1);
    }

    @Test
    @DisplayName("Base32 do RFC 4648 e o endereço que o app lê pelo QR code")
    void base32EEndereco() {
        assertThat(Base32.codificar(SEGREDO)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Base32.decodificar("gezd gnbv gy3t qojq gezd gnbv gy3t qojq")).isEqualTo(SEGREDO);
        assertThatThrownBy(() -> Base32.decodificar("GEZ1")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Totp.endereco("Expedio", "maria", SEGREDO)).isEqualTo(
                "otpauth://totp/Expedio:maria?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Expedio"
                        + "&algorithm=SHA1&digits=6&period=30");
    }
}
