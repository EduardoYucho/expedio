package br.com.plumb.modulos.clientes.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Código de 6 dígitos dos apps autenticadores (TOTP, RFC 6238): HMAC-SHA1, passos de 30 segundos. Aceita o passo
 * de agora e um para cada lado (o relógio do celular pode estar um pouco fora), e nunca um passo já usado: o mesmo
 * código não abre o cofre duas vezes.
 */
public final class Totp {

    public static final int SEGUNDOS_POR_PASSO = 30;
    public static final int DIGITOS = 6;
    /** Passos aceitos antes e depois do atual. */
    static final int JANELA = 1;
    /** Tamanho do segredo novo (160 bits, o recomendado para HMAC-SHA1). */
    public static final int BYTES_DO_SEGREDO = 20;

    private Totp() {
    }

    public static long passo(Instant instante) {
        return Math.floorDiv(instante.getEpochSecond(), SEGUNDOS_POR_PASSO);
    }

    /** O código de um passo (com zeros à esquerda). */
    public static String codigo(byte[] segredo, long passo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(segredo, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(passo).array());
            int deslocamento = hash[hash.length - 1] & 0x0f;
            int binario = ((hash[deslocamento] & 0x7f) << 24) | ((hash[deslocamento + 1] & 0xff) << 16)
                    | ((hash[deslocamento + 2] & 0xff) << 8) | (hash[deslocamento + 3] & 0xff);
            return String.format("%0" + DIGITOS + "d", binario % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA1 indisponível", e);
        }
    }

    /**
     * Confere um código digitado.
     *
     * @param ultimoUsado o último passo já aceito para este segredo ({@code -1} = nenhum)
     * @return o passo aceito, ou {@code -1} se o código não vale (errado, fora da janela ou já usado)
     */
    public static long conferir(byte[] segredo, String digitado, Instant agora, long ultimoUsado) {
        String codigo = digitado == null ? "" : digitado.replaceAll("\\s", "");
        if (!codigo.matches("\\d{" + DIGITOS + "}")) {
            return -1;
        }
        long atual = passo(agora);
        long aceito = -1;
        for (long p = atual - JANELA; p <= atual + JANELA; p++) {
            // compara todos (tempo constante), sem parar no primeiro
            boolean igual = MessageDigest.isEqual(codigo(segredo, p).getBytes(StandardCharsets.US_ASCII),
                    codigo.getBytes(StandardCharsets.US_ASCII));
            if (igual && p > ultimoUsado && aceito < 0) {
                aceito = p;
            }
        }
        return aceito;
    }

    /** O endereço que o app lê pelo QR code. */
    public static String endereco(String emissor, String conta, byte[] segredo) {
        String rotulo = enc(emissor) + ":" + enc(conta);
        return "otpauth://totp/" + rotulo + "?secret=" + Base32.codificar(segredo) + "&issuer=" + enc(emissor)
                + "&algorithm=SHA1&digits=" + DIGITOS + "&period=" + SEGUNDOS_POR_PASSO;
    }

    private static String enc(String texto) {
        return URLEncoder.encode(texto, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
