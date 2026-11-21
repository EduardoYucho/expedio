package br.com.plumb.modulos.clientes.application.ficha;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Onde as fichas ficam (o schema {@code clientes}). Nenhum método lê ou devolve senha. */
public interface CadastroDeClientes {

    /** Situação: {@code ativos}, {@code inativos} ou {@code todos}. O termo já vem sem acento e em minúsculas. */
    List<Ficha.Resumo> buscar(String termo, String situacao, int limite);

    List<String> grupos();

    Optional<Ficha.Completa> ficha(UUID id);

    boolean existe(UUID id);

    UUID criar(Ficha.PedidoCliente pedido, String quem);

    void alterar(UUID id, Ficha.PedidoCliente pedido, String quem);

    void apagar(UUID id);

    UUID criarAlerta(UUID cliente, Ficha.PedidoAlerta pedido, String quem);

    boolean alterarAlerta(UUID cliente, UUID id, Ficha.PedidoAlerta pedido);

    boolean apagarAlerta(UUID cliente, UUID id);

    UUID criarContato(UUID cliente, Ficha.PedidoContato pedido);

    boolean alterarContato(UUID cliente, UUID id, Ficha.PedidoContato pedido);

    boolean apagarContato(UUID cliente, UUID id);

    UUID criarMaquina(UUID cliente, Ficha.PedidoMaquina pedido);

    boolean alterarMaquina(UUID cliente, UUID id, Ficha.PedidoMaquina pedido);

    boolean apagarMaquina(UUID cliente, UUID id);

    /** A máquina é deste cliente? (o acesso só aponta para máquina do mesmo cliente) */
    boolean maquinaDoCliente(UUID cliente, UUID maquina);

    UUID criarAcesso(UUID cliente, Ficha.PedidoAcesso pedido, String quem);

    boolean alterarAcesso(UUID cliente, UUID id, Ficha.PedidoAcesso pedido, String quem);

    boolean apagarAcesso(UUID cliente, UUID id);

    UUID criarAnotacao(UUID cliente, String texto, String autor);

    /** O autor da anotação (para só ele ou quem administra apagar). */
    Optional<String> autorDaAnotacao(UUID cliente, UUID id);

    boolean apagarAnotacao(UUID cliente, UUID id);
}
