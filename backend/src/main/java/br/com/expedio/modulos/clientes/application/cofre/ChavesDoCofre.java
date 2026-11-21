package br.com.expedio.modulos.clientes.application.cofre;

import java.util.UUID;

/**
 * A cifra do cofre (AES-256-GCM com a chave do cofre, fora do banco). O dono (o id do acesso, ou do usuário no caso do
 * autenticador) entra como dado associado: um valor cifrado só abre para o mesmo dono.
 */
public interface ChavesDoCofre {

    record Cifrado(byte[] dados, byte[] vetor) {
    }

    enum Situacao {
        /** A chave existe e abre. */
        PRONTA,
        /** Ainda não há chave nem dado cifrado: nasce no primeiro uso. */
        NOVA,
        /** Não há chave, mas há dados cifrados ou a cópia embrulhada: só a frase de recuperação abre. */
        TRANCADA
    }

    Cifrado cifrar(String texto, UUID dono);

    String decifrar(Cifrado cifrado, UUID dono);

    Situacao situacao();

    /** Já existe a cópia da chave embrulhada pela frase de recuperação? */
    boolean temRecuperacao();

    /** Embrulha a chave atual com a frase (substitui a cópia anterior). */
    void definirFraseDeRecuperacao(String frase);

    /** Recria a chave a partir da cópia embrulhada (cofre trancado). Frase errada: {@code FRASE_ERRADA}. */
    void restaurarComFrase(String frase);
}
