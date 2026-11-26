package br.com.plumb.modulos.clientes.application.cofre;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** O que o cofre guarda no banco: as senhas cifradas, os autenticadores e o registro de uso das senhas. */
public interface RegistroDoCofre {

    record Autenticador(UUID usuarioId, ChavesDoCofre.Cifrado segredo, Instant ativadoEm, long ultimoPasso) {
        public boolean ativo() {
            return ativadoEm != null;
        }
    }

    record AutenticadorDaPessoa(UUID usuarioId, String login, String nome, Instant ativadoEm) {
    }

    record SenhaGuardada(AlvoDoCofre alvo, UUID clienteId, String descricao, ChavesDoCofre.Cifrado senha) {
    }

    /** {@code acessoId}: o acesso (senha da máquina ou da rede); {@code credencialId}: o usuário do cliente. */
    record Uso(long id, Instant em, String login, String acao, UUID clienteId, String cliente, UUID acessoId,
               UUID credencialId, String descricao, String ip) {
    }

    /** Há algo cifrado com a chave do cofre (senha ou autenticador)? */
    boolean haDadosCifrados();

    Optional<Autenticador> autenticador(UUID usuarioId);

    /** Grava um autenticador novo, ainda não ativado (substitui um pendente). */
    void novoAutenticador(UUID usuarioId, ChavesDoCofre.Cifrado segredo);

    void ativarAutenticador(UUID usuarioId, long passo, Instant em);

    /** Marca o passo como usado; {@code false} se ele (ou um mais novo) já tinha sido usado: código repetido. */
    boolean usarPasso(UUID usuarioId, long passo);

    boolean apagarAutenticador(UUID usuarioId);

    List<AutenticadorDaPessoa> autenticadores();

    /** A senha guardada no alvo ({@code senha} nula: sem senha). Vazio: o acesso ou o usuário não existe. */
    Optional<SenhaGuardada> senha(AlvoDoCofre alvo);

    void gravarSenha(AlvoDoCofre alvo, ChavesDoCofre.Cifrado senha, String quem, Instant em);

    /** {@code alvo} nulo: ação do cofre que não é sobre uma senha (abrir, autenticador…). */
    void registrarUso(String login, String acao, UUID clienteId, AlvoDoCofre alvo, String descricao, String ip);

    /** O registro de uso, o mais recente primeiro ({@code cliente} nulo: de todos). */
    List<Uso> usos(UUID cliente, int limite);
}
