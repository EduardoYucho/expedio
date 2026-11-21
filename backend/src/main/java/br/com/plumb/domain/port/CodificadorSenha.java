package br.com.plumb.domain.port;

/** Hash de senha (BCrypt na infraestrutura). */
public interface CodificadorSenha {

    String codificar(String senhaAberta);

    boolean confere(String senhaAberta, String hash);
}
