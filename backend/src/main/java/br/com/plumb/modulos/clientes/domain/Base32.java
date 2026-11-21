package br.com.plumb.modulos.clientes.domain;

/** Base32 (RFC 4648, sem "="): o formato do segredo que os apps autenticadores aceitam digitado. */
public final class Base32 {

    private static final String ALFABETO = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {
    }

    public static String codificar(byte[] dados) {
        StringBuilder saida = new StringBuilder((dados.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : dados) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                saida.append(ALFABETO.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            saida.append(ALFABETO.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return saida.toString();
    }

    public static byte[] decodificar(String texto) {
        String limpo = texto.replaceAll("[\\s=-]", "").toUpperCase();
        byte[] saida = new byte[limpo.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int i = 0;
        for (char c : limpo.toCharArray()) {
            int valor = ALFABETO.indexOf(c);
            if (valor < 0) {
                throw new IllegalArgumentException("caractere fora do Base32: " + c);
            }
            buffer = (buffer << 5) | valor;
            bits += 5;
            if (bits >= 8) {
                saida[i++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
            }
        }
        return saida;
    }
}
