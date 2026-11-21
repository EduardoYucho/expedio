package br.com.plumb.application.evento;

import br.com.plumb.domain.model.Notificacao;

public record NotificacaoCriadaEvento(Notificacao notificacao, int naoLidas) {
}
