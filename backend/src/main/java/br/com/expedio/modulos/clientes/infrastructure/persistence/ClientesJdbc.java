package br.com.expedio.modulos.clientes.infrastructure.persistence;

import br.com.expedio.modulos.clientes.application.cofre.ChavesDoCofre;
import br.com.expedio.modulos.clientes.application.cofre.RegistroDoCofre;
import br.com.expedio.modulos.clientes.application.ficha.CadastroDeClientes;
import br.com.expedio.modulos.clientes.application.ficha.Ficha;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * As fichas e o cofre no schema {@code clientes}. As consultas da ficha nunca selecionam as colunas da senha; só
 * {@link #senha} as lê, e só o cofre a chama.
 */
@Repository
public class ClientesJdbc implements CadastroDeClientes, RegistroDoCofre {

    private final JdbcClient jdbc;
    private final TransactionOperations transacao;

    public ClientesJdbc(JdbcClient jdbc, TransactionOperations transacao) {
        this.jdbc = jdbc;
        this.transacao = transacao;
    }

    // ================================================================================== ficha

    @Override
    public List<Ficha.Resumo> buscar(String termo, String situacao, int limite) {
        String padrao = "%" + escaparLike(termo) + "%";
        String padraoSemEspaco = "%" + escaparLike(termo.replace(" ", "")) + "%";
        return jdbc.sql("""
                        SELECT c.id, c.nome, g.nome AS grupo, c.cidade, c.ativo, c.codigo_chamados,
                               (SELECT count(*) FROM clientes.alerta a WHERE a.cliente_id = c.id AND a.ativo) AS alertas,
                               (SELECT string_agg(DISTINCT x.tipo, ',' ORDER BY x.tipo) FROM clientes.acesso x
                                 WHERE x.cliente_id = c.id) AS tipos
                          FROM clientes.cliente c
                          LEFT JOIN clientes.grupo g ON g.id = c.grupo_id
                         WHERE (:situacao = 'todos' OR c.ativo = (:situacao = 'ativos'))
                           AND (:termo = ''
                             OR clientes.sem_acento(lower(c.nome || ' ' || coalesce(c.cidade, '') || ' '
                                    || coalesce(g.nome, '') || ' ' || coalesce(c.documento, '') || ' '
                                    || coalesce(c.codigo_chamados, ''))) LIKE :padrao ESCAPE '\\'
                             OR EXISTS (SELECT 1 FROM clientes.contato ct WHERE ct.cliente_id = c.id
                                    AND clientes.sem_acento(lower(ct.nome || ' ' || coalesce(ct.telefone, '')))
                                        LIKE :padrao ESCAPE '\\')
                             OR EXISTS (SELECT 1 FROM clientes.acesso ac WHERE ac.cliente_id = c.id
                                    AND regexp_replace(lower(ac.identificador), '\\s', '', 'g') LIKE :semEspaco ESCAPE '\\'))
                         ORDER BY g.nome NULLS LAST, c.nome
                         LIMIT :limite""")
                .param("situacao", situacao)
                .param("termo", termo)
                .param("padrao", padrao)
                .param("semEspaco", padraoSemEspaco)
                .param("limite", limite)
                .query((rs, i) -> new Ficha.Resumo(uuid(rs, "id"), rs.getString("nome"), rs.getString("grupo"),
                        rs.getString("cidade"), rs.getBoolean("ativo"), rs.getString("codigo_chamados"),
                        rs.getInt("alertas"), tipos(rs.getString("tipos"))))
                .list();
    }

    @Override
    public List<String> grupos() {
        return jdbc.sql("SELECT nome FROM clientes.grupo ORDER BY lower(nome)").query(String.class).list();
    }

    @Override
    public Optional<Ficha.Completa> ficha(UUID id) {
        Optional<Ficha.Cliente> cliente = jdbc.sql("""
                        SELECT c.id, c.nome, g.nome AS grupo, c.documento, c.cidade, c.ativo, c.codigo_chamados,
                               c.observacao, c.criado_em, c.criado_por, c.atualizado_em, c.atualizado_por
                          FROM clientes.cliente c LEFT JOIN clientes.grupo g ON g.id = c.grupo_id
                         WHERE c.id = :id""")
                .param("id", id)
                .query((rs, i) -> new Ficha.Cliente(uuid(rs, "id"), rs.getString("nome"), rs.getString("grupo"),
                        rs.getString("documento"), rs.getString("cidade"), rs.getBoolean("ativo"),
                        rs.getString("codigo_chamados"), rs.getString("observacao"), instante(rs, "criado_em"),
                        rs.getString("criado_por"), instante(rs, "atualizado_em"), rs.getString("atualizado_por")))
                .optional();
        return cliente.map(c -> new Ficha.Completa(c,
                jdbc.sql("""
                                SELECT id, texto, ativo, criado_em, criado_por FROM clientes.alerta
                                 WHERE cliente_id = :id ORDER BY ativo DESC, criado_em DESC""")
                        .param("id", id)
                        .query((rs, i) -> new Ficha.Alerta(uuid(rs, "id"), rs.getString("texto"), rs.getBoolean("ativo"),
                                instante(rs, "criado_em"), rs.getString("criado_por")))
                        .list(),
                jdbc.sql("""
                                SELECT id, nome, funcao, telefone, email, autoriza, observacao FROM clientes.contato
                                 WHERE cliente_id = :id ORDER BY autoriza DESC, lower(nome)""")
                        .param("id", id)
                        .query((rs, i) -> new Ficha.Contato(uuid(rs, "id"), rs.getString("nome"), rs.getString("funcao"),
                                rs.getString("telefone"), rs.getString("email"), rs.getBoolean("autoriza"),
                                rs.getString("observacao")))
                        .list(),
                jdbc.sql("""
                                SELECT id, nome, papel, pasta_sistema, observacao FROM clientes.maquina
                                 WHERE cliente_id = :id
                                 ORDER BY CASE papel WHEN 'SERVIDOR_CENTRAL' THEN 0 WHEN 'SERVIDOR' THEN 1 ELSE 2 END,
                                          lower(nome)""")
                        .param("id", id)
                        .query((rs, i) -> new Ficha.Maquina(uuid(rs, "id"), rs.getString("nome"), rs.getString("papel"),
                                rs.getString("pasta_sistema"), rs.getString("observacao")))
                        .list(),
                jdbc.sql("""
                                SELECT id, maquina_id, tipo, identificador, porta, usuario, observacao,
                                       senha_cifrada IS NOT NULL AS tem_senha, senha_alterada_em, senha_alterada_por,
                                       atualizado_em, atualizado_por
                                  FROM clientes.acesso WHERE cliente_id = :id ORDER BY tipo, criado_em""")
                        .param("id", id)
                        .query((rs, i) -> new Ficha.Acesso(uuid(rs, "id"), uuid(rs, "maquina_id"), rs.getString("tipo"),
                                rs.getString("identificador"), (Integer) rs.getObject("porta"), rs.getString("usuario"),
                                rs.getString("observacao"), rs.getBoolean("tem_senha"), instante(rs, "senha_alterada_em"),
                                rs.getString("senha_alterada_por"), instante(rs, "atualizado_em"),
                                rs.getString("atualizado_por")))
                        .list(),
                jdbc.sql("""
                                SELECT id, texto, autor, criada_em FROM clientes.anotacao
                                 WHERE cliente_id = :id ORDER BY criada_em DESC LIMIT 200""")
                        .param("id", id)
                        .query((rs, i) -> new Ficha.Anotacao(uuid(rs, "id"), rs.getString("texto"), rs.getString("autor"),
                                instante(rs, "criada_em")))
                        .list()));
    }

    @Override
    public boolean existe(UUID id) {
        return jdbc.sql("SELECT count(*) FROM clientes.cliente WHERE id = :id").param("id", id)
                .query(Integer.class).single() > 0;
    }

    @Override
    public UUID criar(Ficha.PedidoCliente p, String quem) {
        UUID id = UUID.randomUUID();
        transacao.executeWithoutResult(t -> jdbc.sql("""
                        INSERT INTO clientes.cliente (id, nome, grupo_id, documento, cidade, ativo, codigo_chamados,
                                                      observacao, criado_por, atualizado_por)
                        VALUES (:id, :nome, :grupo, :documento, :cidade, :ativo, :codigo, :observacao, :quem, :quem)""")
                .param("id", id)
                .param("nome", p.nome())
                .param("grupo", grupo(p.grupo()))
                .param("documento", p.documento())
                .param("cidade", p.cidade())
                .param("ativo", p.ativo())
                .param("codigo", p.codigoChamados())
                .param("observacao", p.observacao())
                .param("quem", quem)
                .update());
        return id;
    }

    @Override
    public void alterar(UUID id, Ficha.PedidoCliente p, String quem) {
        transacao.executeWithoutResult(t -> jdbc.sql("""
                        UPDATE clientes.cliente SET nome = :nome, grupo_id = :grupo, documento = :documento,
                               cidade = :cidade, ativo = :ativo, codigo_chamados = :codigo, observacao = :observacao,
                               atualizado_em = now(), atualizado_por = :quem
                         WHERE id = :id""")
                .param("id", id)
                .param("nome", p.nome())
                .param("grupo", grupo(p.grupo()))
                .param("documento", p.documento())
                .param("cidade", p.cidade())
                .param("ativo", p.ativo())
                .param("codigo", p.codigoChamados())
                .param("observacao", p.observacao())
                .param("quem", quem)
                .update());
    }

    @Override
    public void apagar(UUID id) {
        jdbc.sql("DELETE FROM clientes.cliente WHERE id = :id").param("id", id).update();
    }

    /** O grupo pelo nome (sem diferença de maiúsculas), criado se não existe. */
    private UUID grupo(String nome) {
        if (nome == null) {
            return null;
        }
        jdbc.sql("""
                        INSERT INTO clientes.grupo (id, nome) VALUES (:id, :nome)
                        ON CONFLICT ((lower(nome))) DO NOTHING""")
                .param("id", UUID.randomUUID()).param("nome", nome).update();
        return jdbc.sql("SELECT id FROM clientes.grupo WHERE lower(nome) = lower(:nome)").param("nome", nome)
                .query(UUID.class).single();
    }

    // ---------------------------------------------------------------------------------- itens

    @Override
    public UUID criarAlerta(UUID cliente, Ficha.PedidoAlerta p, String quem) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO clientes.alerta (id, cliente_id, texto, ativo, criado_por)
                        VALUES (:id, :cliente, :texto, :ativo, :quem)""")
                .param("id", id).param("cliente", cliente).param("texto", p.texto()).param("ativo", p.ativo())
                .param("quem", quem).update();
        return id;
    }

    @Override
    public boolean alterarAlerta(UUID cliente, UUID id, Ficha.PedidoAlerta p) {
        return jdbc.sql("UPDATE clientes.alerta SET texto = :texto, ativo = :ativo WHERE id = :id AND cliente_id = :cliente")
                .param("id", id).param("cliente", cliente).param("texto", p.texto()).param("ativo", p.ativo())
                .update() > 0;
    }

    @Override
    public boolean apagarAlerta(UUID cliente, UUID id) {
        return apagarItem("alerta", cliente, id);
    }

    @Override
    public UUID criarContato(UUID cliente, Ficha.PedidoContato p) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO clientes.contato (id, cliente_id, nome, funcao, telefone, email, autoriza, observacao)
                        VALUES (:id, :cliente, :nome, :funcao, :telefone, :email, :autoriza, :observacao)""")
                .param("id", id).param("cliente", cliente).param("nome", p.nome()).param("funcao", p.funcao())
                .param("telefone", p.telefone()).param("email", p.email()).param("autoriza", p.autoriza())
                .param("observacao", p.observacao()).update();
        return id;
    }

    @Override
    public boolean alterarContato(UUID cliente, UUID id, Ficha.PedidoContato p) {
        return jdbc.sql("""
                        UPDATE clientes.contato SET nome = :nome, funcao = :funcao, telefone = :telefone, email = :email,
                               autoriza = :autoriza, observacao = :observacao
                         WHERE id = :id AND cliente_id = :cliente""")
                .param("id", id).param("cliente", cliente).param("nome", p.nome()).param("funcao", p.funcao())
                .param("telefone", p.telefone()).param("email", p.email()).param("autoriza", p.autoriza())
                .param("observacao", p.observacao()).update() > 0;
    }

    @Override
    public boolean apagarContato(UUID cliente, UUID id) {
        return apagarItem("contato", cliente, id);
    }

    @Override
    public UUID criarMaquina(UUID cliente, Ficha.PedidoMaquina p) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO clientes.maquina (id, cliente_id, nome, papel, pasta_sistema, observacao)
                        VALUES (:id, :cliente, :nome, :papel, :pasta, :observacao)""")
                .param("id", id).param("cliente", cliente).param("nome", p.nome()).param("papel", p.papel())
                .param("pasta", p.pastaSistema()).param("observacao", p.observacao()).update();
        return id;
    }

    @Override
    public boolean alterarMaquina(UUID cliente, UUID id, Ficha.PedidoMaquina p) {
        return jdbc.sql("""
                        UPDATE clientes.maquina SET nome = :nome, papel = :papel, pasta_sistema = :pasta,
                               observacao = :observacao
                         WHERE id = :id AND cliente_id = :cliente""")
                .param("id", id).param("cliente", cliente).param("nome", p.nome()).param("papel", p.papel())
                .param("pasta", p.pastaSistema()).param("observacao", p.observacao()).update() > 0;
    }

    @Override
    public boolean apagarMaquina(UUID cliente, UUID id) {
        return apagarItem("maquina", cliente, id);
    }

    @Override
    public boolean maquinaDoCliente(UUID cliente, UUID maquina) {
        return jdbc.sql("SELECT count(*) FROM clientes.maquina WHERE id = :id AND cliente_id = :cliente")
                .param("id", maquina).param("cliente", cliente).query(Integer.class).single() > 0;
    }

    @Override
    public UUID criarAcesso(UUID cliente, Ficha.PedidoAcesso p, String quem) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO clientes.acesso (id, cliente_id, maquina_id, tipo, identificador, porta, usuario,
                                                     observacao, atualizado_por)
                        VALUES (:id, :cliente, :maquina, :tipo, :identificador, :porta, :usuario, :observacao, :quem)""")
                .param("id", id).param("cliente", cliente).param("maquina", p.maquinaId()).param("tipo", p.tipo())
                .param("identificador", p.identificador()).param("porta", p.porta()).param("usuario", p.usuario())
                .param("observacao", p.observacao()).param("quem", quem).update();
        return id;
    }

    @Override
    public boolean alterarAcesso(UUID cliente, UUID id, Ficha.PedidoAcesso p, String quem) {
        return jdbc.sql("""
                        UPDATE clientes.acesso SET maquina_id = :maquina, tipo = :tipo, identificador = :identificador,
                               porta = :porta, usuario = :usuario, observacao = :observacao,
                               atualizado_em = now(), atualizado_por = :quem
                         WHERE id = :id AND cliente_id = :cliente""")
                .param("id", id).param("cliente", cliente).param("maquina", p.maquinaId()).param("tipo", p.tipo())
                .param("identificador", p.identificador()).param("porta", p.porta()).param("usuario", p.usuario())
                .param("observacao", p.observacao()).param("quem", quem).update() > 0;
    }

    @Override
    public boolean apagarAcesso(UUID cliente, UUID id) {
        return apagarItem("acesso", cliente, id);
    }

    @Override
    public UUID criarAnotacao(UUID cliente, String texto, String autor) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO clientes.anotacao (id, cliente_id, texto, autor) VALUES (:id, :cliente, :texto, :autor)")
                .param("id", id).param("cliente", cliente).param("texto", texto).param("autor", autor).update();
        return id;
    }

    @Override
    public Optional<String> autorDaAnotacao(UUID cliente, UUID id) {
        return jdbc.sql("SELECT autor FROM clientes.anotacao WHERE id = :id AND cliente_id = :cliente")
                .param("id", id).param("cliente", cliente).query(String.class).optional();
    }

    @Override
    public boolean apagarAnotacao(UUID cliente, UUID id) {
        return apagarItem("anotacao", cliente, id);
    }

    /** A tabela vem só das constantes acima (nunca do pedido). */
    private boolean apagarItem(String tabela, UUID cliente, UUID id) {
        if (!List.of("alerta", "contato", "maquina", "acesso", "anotacao").contains(tabela)) {
            throw new IllegalArgumentException(tabela);
        }
        return jdbc.sql("DELETE FROM clientes." + tabela + " WHERE id = :id AND cliente_id = :cliente")
                .param("id", id).param("cliente", cliente).update() > 0;
    }

    // ================================================================================== cofre

    @Override
    public boolean haDadosCifrados() {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM clientes.acesso WHERE senha_cifrada IS NOT NULL)
                            OR EXISTS (SELECT 1 FROM clientes.autenticador)""")
                .query(Boolean.class).single();
    }

    @Override
    public Optional<Autenticador> autenticador(UUID usuarioId) {
        return jdbc.sql("""
                        SELECT usuario_id, segredo_cifrado, segredo_vetor, ativado_em, ultimo_passo
                          FROM clientes.autenticador WHERE usuario_id = :id""")
                .param("id", usuarioId)
                .query((rs, i) -> new Autenticador(uuid(rs, "usuario_id"),
                        new ChavesDoCofre.Cifrado(rs.getBytes("segredo_cifrado"), rs.getBytes("segredo_vetor")),
                        instante(rs, "ativado_em"), rs.getLong("ultimo_passo")))
                .optional();
    }

    @Override
    public void novoAutenticador(UUID usuarioId, ChavesDoCofre.Cifrado segredo) {
        jdbc.sql("""
                        INSERT INTO clientes.autenticador (usuario_id, segredo_cifrado, segredo_vetor)
                        VALUES (:id, :dados, :vetor)
                        ON CONFLICT (usuario_id) DO UPDATE
                           SET segredo_cifrado = EXCLUDED.segredo_cifrado, segredo_vetor = EXCLUDED.segredo_vetor,
                               ativado_em = NULL, ultimo_passo = -1, criado_em = now()
                         WHERE clientes.autenticador.ativado_em IS NULL""")
                .param("id", usuarioId).param("dados", segredo.dados()).param("vetor", segredo.vetor()).update();
    }

    @Override
    public void ativarAutenticador(UUID usuarioId, long passo, Instant em) {
        jdbc.sql("""
                        UPDATE clientes.autenticador SET ativado_em = :em, ultimo_passo = :passo
                         WHERE usuario_id = :id AND ativado_em IS NULL""")
                .param("id", usuarioId).param("em", Timestamp.from(em)).param("passo", passo).update();
    }

    @Override
    public boolean usarPasso(UUID usuarioId, long passo) {
        return jdbc.sql("""
                        UPDATE clientes.autenticador SET ultimo_passo = :passo
                         WHERE usuario_id = :id AND ativado_em IS NOT NULL AND ultimo_passo < :passo""")
                .param("id", usuarioId).param("passo", passo).update() > 0;
    }

    @Override
    public boolean apagarAutenticador(UUID usuarioId) {
        return jdbc.sql("DELETE FROM clientes.autenticador WHERE usuario_id = :id").param("id", usuarioId).update() > 0;
    }

    @Override
    public List<AutenticadorDaPessoa> autenticadores() {
        return jdbc.sql("""
                        SELECT a.usuario_id, u.login, u.nome, a.ativado_em
                          FROM clientes.autenticador a JOIN public.tb_usuario u ON u.id = a.usuario_id
                         ORDER BY lower(u.nome)""")
                .query((rs, i) -> new AutenticadorDaPessoa(uuid(rs, "usuario_id"), rs.getString("login"),
                        rs.getString("nome"), instante(rs, "ativado_em")))
                .list();
    }

    @Override
    public Optional<SenhaGuardada> senha(UUID acessoId) {
        return jdbc.sql("""
                        SELECT a.id, a.cliente_id, c.nome AS cliente, a.tipo, a.identificador, a.senha_cifrada, a.senha_vetor
                          FROM clientes.acesso a JOIN clientes.cliente c ON c.id = a.cliente_id
                         WHERE a.id = :id""")
                .param("id", acessoId)
                .query((rs, i) -> {
                    byte[] dados = rs.getBytes("senha_cifrada");
                    String descricao = rs.getString("cliente") + " · " + rs.getString("tipo") + " "
                            + rs.getString("identificador");
                    return new SenhaGuardada(uuid(rs, "id"), uuid(rs, "cliente_id"),
                            descricao.length() > 300 ? descricao.substring(0, 300) : descricao,
                            dados == null ? null : new ChavesDoCofre.Cifrado(dados, rs.getBytes("senha_vetor")));
                })
                .optional();
    }

    @Override
    public void gravarSenha(UUID acessoId, ChavesDoCofre.Cifrado senha, String quem, Instant em) {
        jdbc.sql("""
                        UPDATE clientes.acesso SET senha_cifrada = :dados, senha_vetor = :vetor,
                               senha_alterada_em = :em, senha_alterada_por = :quem
                         WHERE id = :id""")
                .param("id", acessoId)
                .param("dados", senha == null ? null : senha.dados())
                .param("vetor", senha == null ? null : senha.vetor())
                .param("em", Timestamp.from(em))
                .param("quem", quem)
                .update();
    }

    @Override
    public void registrarUso(String login, String acao, UUID clienteId, UUID acessoId, String descricao, String ip) {
        jdbc.sql("""
                        INSERT INTO clientes.uso_de_senha (login, acao, cliente_id, acesso_id, descricao, ip)
                        VALUES (:login, :acao, :cliente, :acesso, :descricao, :ip)""")
                .param("login", login).param("acao", acao).param("cliente", clienteId).param("acesso", acessoId)
                .param("descricao", descricao)
                .param("ip", ip == null ? null : ip.length() > 45 ? ip.substring(0, 45) : ip)
                .update();
    }

    @Override
    public List<Uso> usos(UUID cliente, int limite) {
        return jdbc.sql("""
                        SELECT u.id, u.em, u.login, u.acao, u.cliente_id, c.nome AS cliente, u.acesso_id, u.descricao, u.ip
                          FROM clientes.uso_de_senha u LEFT JOIN clientes.cliente c ON c.id = u.cliente_id
                         WHERE (CAST(:cliente AS UUID) IS NULL OR u.cliente_id = :cliente)
                         ORDER BY u.em DESC, u.id DESC LIMIT :limite""")
                .param("cliente", cliente).param("limite", limite)
                .query((rs, i) -> new Uso(rs.getLong("id"), instante(rs, "em"), rs.getString("login"),
                        rs.getString("acao"), uuid(rs, "cliente_id"), rs.getString("cliente"), uuid(rs, "acesso_id"),
                        rs.getString("descricao"), rs.getString("ip")))
                .list();
    }

    // ================================================================================== auxiliares

    private static UUID uuid(ResultSet rs, String coluna) throws SQLException {
        return rs.getObject(coluna, UUID.class);
    }

    private static Instant instante(ResultSet rs, String coluna) throws SQLException {
        Timestamp t = rs.getTimestamp(coluna);
        return t == null ? null : t.toInstant();
    }

    private static List<String> tipos(String juntos) {
        return juntos == null || juntos.isBlank() ? List.of() : Arrays.asList(juntos.split(","));
    }

    static String escaparLike(String texto) {
        return texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
