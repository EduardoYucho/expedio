package br.com.plumb.application.evento;

import java.util.UUID;

import br.com.plumb.domain.model.StatusImportacao;

import java.time.LocalDateTime;

/** Um PDF foi lido, mas não gerou batida (duplicado, rejeitado ou inválido). */
public record ComprovanteNaoImportadoEvento(
        UUID usuarioId,
        String nomeArquivo,
        StatusImportacao status,
        LocalDateTime dataHoraBatida,
        String mensagem) {
}
