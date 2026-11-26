package br.com.plumb.modulos.clientes.application.ficha;

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

    /** Uma máquina do cliente: rede, sistema e hardware (tudo opcional, além do nome e do papel). */
    public record Maquina(UUID id, String nome, String papel, String nomeComputador, String ipLocal, String ipRadmin,
                          String sistemaOperacional, String pastaSistema, String bancoDados, String localBase,
                          String processador, String memoria, String disco, String localBackup, String observacao) {
    }

    /**
     * Um acesso remoto: o ID/endereço aparece; as senhas não (só se existem e quando mudaram). {@code rede}: a rede para
     * entrar antes (Radmin VPN, VPN), com a senha dela; {@code usuario} e a senha: o login da máquina.
     */
    public record Acesso(UUID id, UUID maquinaId, String tipo, String identificador, Integer porta, String rede,
                         boolean temSenhaDaRede, Instant senhaDaRedeAlteradaEm, String senhaDaRedeAlteradaPor,
                         String usuario, String observacao, boolean temSenha, Instant senhaAlteradaEm,
                         String senhaAlteradaPor, Instant atualizadoEm, String atualizadoPor) {
    }

    /** Um usuário e senha do cliente (sistema da loja, Windows, banco, outros serviços): a senha não aparece. */
    public record Credencial(UUID id, UUID maquinaId, String onde, String servico, String usuario, String observacao,
                             boolean temSenha, Instant senhaAlteradaEm, String senhaAlteradaPor, Instant atualizadoEm,
                             String atualizadoPor) {
    }

    public record Anotacao(UUID id, String texto, String autor, Instant criadaEm) {
    }

    public record Completa(Cliente cliente, List<Alerta> alertas, List<Contato> contatos, List<Maquina> maquinas,
                           List<Acesso> acessos, List<Credencial> credenciais, List<Anotacao> anotacoes) {
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

    public record PedidoMaquina(String nome, String papel, String nomeComputador, String ipLocal, String ipRadmin,
                                String sistemaOperacional, String pastaSistema, String bancoDados, String localBase,
                                String processador, String memoria, String disco, String localBackup,
                                String observacao) {
    }

    /** O acesso sem as senhas: elas vão pelo cofre ({@code PUT .../cofre/acessos/{id}/senha}, com o campo). */
    public record PedidoAcesso(UUID maquinaId, String tipo, String identificador, Integer porta, String rede,
                               String usuario, String observacao) {
    }

    /** O usuário do cliente sem a senha: ela vai pelo cofre ({@code PUT .../cofre/credenciais/{id}/senha}). */
    public record PedidoCredencial(UUID maquinaId, String onde, String servico, String usuario, String observacao) {
    }

    public record PedidoAnotacao(String texto) {
    }
}
