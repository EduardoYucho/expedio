package br.com.plumb.application.view;

import br.com.plumb.domain.model.ComprovanteArquivado;

/** Conteúdo de um comprovante pronto para download (integridade já conferida). */
public record ArquivoComprovanteView(ComprovanteArquivado comprovante, byte[] conteudo) {
}
