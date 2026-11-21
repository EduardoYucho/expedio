package br.com.plumb.modulos.clientes.domain;

/** Como se chega ao cliente. */
public enum TipoDeAcesso {
    ANYDESK("AnyDesk"),
    ULTRAVIEWER("UltraViewer"),
    RADMIN("Radmin VPN"),
    RDP("WTS / Área de trabalho remota"),
    TEAMVIEWER("TeamViewer"),
    VPN("VPN"),
    OUTRO("Outro");

    private final String rotulo;

    TipoDeAcesso(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
