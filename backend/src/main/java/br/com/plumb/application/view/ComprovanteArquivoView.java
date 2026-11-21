package br.com.plumb.application.view;

import br.com.plumb.domain.model.ComprovanteArquivado;

import java.net.URI;

/** Comprovante arquivado + URI de download. */
public record ComprovanteArquivoView(ComprovanteArquivado comprovante, URI uriDownload) {
}
