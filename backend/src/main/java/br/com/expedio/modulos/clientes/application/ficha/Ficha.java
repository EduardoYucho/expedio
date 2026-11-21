package br.com.expedio.modulos.clientes.application.ficha;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** O que a tela lê e grava da ficha de um cliente. Nenhum destes registros leva senha. */
public final class Ficha {

    private Ficha() {
    }

    /** Uma linha da lista de clientes. */
    public record Resumo(UUID id, String nome, String grupo, String cidade, boolean ativo, String codigoChamados,
                         int alertas, List<String> tiposDeAcesso) {
    }

    public record Cliente(UUID id, String nome, String grupo, String documento, String cidade, boolean ativo,
                          String codigoChamados, String observacao, Instant criadoEm, String criadoPor,
                          Instant atualizadoEm, String atualizadoPor) {
    }

    public record Alerta(UUID id, String texto, boolean ativo, Instant criadoEm, String criadoPor) {
    }

    public record Contato(UUID id, String nome, String funcao, String telefone, String email, boolean autoriza,
                          String observacao) {
    }

    public record Maquina(UUID id, String nome, String papel, String pastaSistema, String observacao) {
    }

    /** Um acesso remoto: o ID/endereço aparece; a senha não (só se existe e quando mudou). */
    public record Acesso(UUID id, UUID maquinaId, String tipo, String identificador, Integer porta, String usuario,
                         String observacao, boolean temSenha, Instant senhaAlteradaEm, String senhaAlteradaPor,
                         Instant atualizadoEm, String atualizadoPor) {
    }

    public record Anotacao(UUID id, String texto, String autor, Instant criadaEm) {
    }

    public record Completa(Cliente cliente, List<Alerta> alertas, List<Contato> contatos, List<Maquina> maquinas,
                           List<Acesso> acessos, List<Anotacao> anotacoes) {
    }

    // ------------------------------------------------------------------------------------------ pedidos

    public record PedidoCliente(String nome, String grupo, String documento, String cidade, Boolean ativo,
                                String codigoChamados, String observacao) {
    }

    public record PedidoAlerta(String texto, Boolean ativo) {
    }

    public record PedidoContato(String nome, String funcao, String telefone, String email, Boolean autoriza,
                                String observacao) {
    }

    public record PedidoMaquina(String nome, String papel, String pastaSistema, String observacao) {
    }

    /** O acesso sem a senha: a senha vai pelo cofre ({@code PUT .../cofre/acessos/{id}/senha}). */
    public record PedidoAcesso(UUID maquinaId, String tipo, String identificador, Integer porta, String usuario,
                               String observacao) {
    }

    public record PedidoAnotacao(String texto) {
    }
}
