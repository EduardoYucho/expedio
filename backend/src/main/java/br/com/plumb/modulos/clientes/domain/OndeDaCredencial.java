package br.com.plumb.modulos.clientes.domain;

/** Onde um usuário e senha do cliente são usados. */
public enum OndeDaCredencial {
    SISTEMA("Sistema da loja"),
    WINDOWS("Windows"),
    BANCO("Banco de dados"),
    OUTRO("Outros serviços");

    private final String rotulo;

    OndeDaCredencial(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
