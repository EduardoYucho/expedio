package br.com.plumb.modulos.clientes.application.cofre;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Qual senha do cofre: a de um acesso (o login da máquina), a da rede de um acesso (Radmin VPN, VPN) ou a de um usuário
 * do cliente. Cada uma é cifrada com um dono próprio (o dado associado do AES-GCM): uma senha copiada para outro lugar
 * no banco (a da rede no lugar da da máquina, por exemplo) não abre.
 */
public record AlvoDoCofre(Tipo tipo, UUID id) {

    public enum Tipo {
        /** A senha do acesso (usuário da máquina). O dono é o próprio id, como desde a primeira versão do cofre. */
        ACESSO,
        /** A senha da rede do acesso. */
        REDE,
        /** A senha de um usuário do cliente (sistema da loja, Windows, banco, outros serviços). */
        CREDENCIAL
    }

    public AlvoDoCofre {
        if (tipo == null || id == null) {
            throw new IllegalArgumentException("alvo do cofre incompleto");
        }
    }

    public static AlvoDoCofre acesso(UUID id, String campo) {
        return new AlvoDoCofre(campo != null && "rede".equals(campo.strip().toLowerCase(Locale.ROOT)) ? Tipo.REDE : Tipo.ACESSO, id);
    }

    public static AlvoDoCofre credencial(UUID id) {
        return new AlvoDoCofre(Tipo.CREDENCIAL, id);
    }

    /** O dono usado na cifra. */
    public UUID dono() {
        return switch (tipo) {
            case ACESSO -> id;
            case REDE -> UUID.nameUUIDFromBytes(("rede-do-acesso:" + id).getBytes(StandardCharsets.UTF_8));
            case CREDENCIAL -> UUID.nameUUIDFromBytes(("credencial:" + id).getBytes(StandardCharsets.UTF_8));
        };
    }
}
