package br.com.plumb.infrastructure.web.dto;

import br.com.plumb.application.evento.ComprovanteNaoImportadoEvento;
import br.com.plumb.domain.model.ComprovanteImportado;
import br.com.plumb.domain.model.StatusImportacao;

import java.time.Instant;
import java.time.LocalDateTime;

/** Comprovante processado (lista de auditoria e evento SSE {@code comprovante-nao-importado}). */
public record ComprovanteResponse(
        String nomeArquivo,
        StatusImportacao status,
        LocalDateTime dataHoraBatida,
        String mensagem,
        Instant processadoEm) {

    public static ComprovanteResponse de(ComprovanteImportado c) {
        return new ComprovanteResponse(c.nomeArquivo(), c.status(), c.dataHoraBatida(), c.mensagem(), c.processadoEm());
    }

    public static ComprovanteResponse de(ComprovanteNaoImportadoEvento e) {
        return new ComprovanteResponse(e.nomeArquivo(), e.status(), e.dataHoraBatida(), e.mensagem(), Instant.now());
    }
}
