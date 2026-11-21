package br.com.plumb.modulos.clientes.domain;

/** O que a máquina é no cliente. */
public enum PapelDaMaquina {
    SERVIDOR_CENTRAL("Servidor central"),
    SERVIDOR("Servidor"),
    CAIXA("Caixa"),
    ESTACAO("Estação"),
    OUTRA("Outra");

    private final String rotulo;

    PapelDaMaquina(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
