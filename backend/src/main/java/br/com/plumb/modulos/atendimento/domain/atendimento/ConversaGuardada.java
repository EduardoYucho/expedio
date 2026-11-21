package br.com.plumb.modulos.atendimento.domain.atendimento;

import br.com.plumb.modulos.atendimento.domain.conversa.Cabecalho;
import br.com.plumb.modulos.atendimento.domain.conversa.ItemDaConversa;
import br.com.plumb.modulos.atendimento.domain.conversa.Omitidos;

import java.util.List;

/** A conversa como ficou guardada no atendimento (já mascarada). */
public record ConversaGuardada(Cabecalho cabecalho, List<ItemDaConversa> itens, Omitidos omitidos) {

    public ConversaGuardada {
        itens = List.copyOf(itens);
    }
}
