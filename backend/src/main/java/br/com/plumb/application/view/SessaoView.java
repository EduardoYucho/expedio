package br.com.plumb.application.view;

import br.com.plumb.domain.model.Usuario;

import java.time.Instant;

public record SessaoView(String token, Instant expiraEm, Usuario usuario) {
}
