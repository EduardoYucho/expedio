package br.com.plumb.modulos.clientes;

import br.com.plumb.domain.acesso.Acesso;
import br.com.plumb.domain.exception.AcessoNegadoException;
import br.com.plumb.domain.exception.MuitasTentativasException;
import br.com.plumb.domain.exception.RegraNegocioException;
import br.com.plumb.domain.model.Usuario;
import br.com.plumb.modulos.PostgresDeTeste;
import br.com.plumb.modulos.clientes.application.acesso.PermissoesDosClientes;
import br.com.plumb.modulos.clientes.application.cofre.AlvoDoCofre;
import br.com.plumb.modulos.clientes.application.cofre.CofreDeAcessos;
import br.com.plumb.modulos.clientes.application.cofre.RegistroDoCofre;
import br.com.plumb.modulos.clientes.application.ficha.Ficha;
import br.com.plumb.modulos.clientes.application.ficha.FichasDeClientes;
import br.com.plumb.modulos.clientes.domain.Base32;
import br.com.plumb.modulos.clientes.domain.Totp;
import br.com.plumb.modulos.clientes.infrastructure.config.ConfiguracaoDosClientes;
import br.com.plumb.modulos.clientes.infrastructure.cripto.ChaveDoCofreEmArquivo;
import br.com.plumb.modulos.clientes.infrastructure.persistence.ClientesJdbc;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A ficha e o cofre de ponta a ponta no PostgreSQL temporário (com a V20 de verdade). */
class ClientesNoBancoTest {

    @TempDir
    Path pasta;

    private final Relogio relogio = new Relogio(Instant.parse("2026-11-21T15:00:00Z"));
    private ClientesJdbc jdbc;
    private FichasDeClientes fichas;
    private CofreDeAcessos cofre;
    private Usuario suporte;
    private Usuario semSenhas;
    private Usuario admin;

    @BeforeEach
    void preparar() {
        var banco = PostgresDeTeste.banco();
        var sql = PostgresDeTeste.jdbc();
        sql.sql("TRUNCATE clientes.cliente, clientes.grupo, clientes.autenticador, clientes.uso_de_senha CASCADE").update();
        jdbc = new ClientesJdbc(sql, new TransactionTemplate(new DataSourceTransactionManager(banco)));
        ChaveDoCofreEmArquivo chave = new ChaveDoCofreEmArquivo(new ConfiguracaoDosClientes(pasta, null), jdbc);
        fichas = new FichasDeClientes(jdbc);
        cofre = new CofreDeAcessos(chave, jdbc, relogio,
                new CofreDeAcessos.Parametros(Duration.ofMinutes(10), 5, Duration.ofMinutes(15)));
        suporte = usuario(sql, "suporte", PermissoesDosClientes.VER, PermissoesDosClientes.EDITAR,
                PermissoesDosClientes.SENHAS);
        semSenhas = usuario(sql, "estagio", PermissoesDosClientes.VER, PermissoesDosClientes.EDITAR);
        admin = usuario(sql, "chefe", PermissoesDosClientes.VER, PermissoesDosClientes.EDITAR,
                PermissoesDosClientes.SENHAS, PermissoesDosClientes.ADMINISTRAR);
    }

    @Test
    @DisplayName("Ficha completa: grupo, alerta, contato, máquina, acesso, anotação; busca por nome, contato e ID")
    void ficha() {
        UUID id = fichas.criar(suporte, new Ficha.PedidoCliente("MH Modas - Urussanga", "MH", null, "Urussanga", true,
                "544", "Loja do centro"));
        fichas.criar(suporte, new Ficha.PedidoCliente("MH Modas - Tubarão", "mh", null, "Tubarão", true, "555", null));
        fichas.criarAlerta(suporte, id, new Ficha.PedidoAlerta("Nunca excluam nada: ligar para Jorge/Duda", null));
        fichas.criarContato(suporte, id, new Ficha.PedidoContato("Jorge", "Dono", "48 99999-0000", null, true, null));
        UUID servidor = fichas.criarMaquina(suporte, id, maquina("Servidor", "servidor_central",
                "C:\\sislojas", null));
        fichas.criarAcesso(suporte, id, acesso(servidor, "anydesk", "344 788 178", null, null, null));
        fichas.criarAnotacao(suporte, id, new Ficha.PedidoAnotacao("Impressora padrão deve ser a HP"));

        assertThat(fichas.grupos()).containsExactly("MH");
        Ficha.Completa f = fichas.ficha(id);
        assertThat(f.cliente().grupo()).isEqualTo("MH");
        assertThat(f.alertas()).singleElement().satisfies(a -> assertThat(a.criadoPor()).isEqualTo("suporte"));
        assertThat(f.maquinas()).singleElement().satisfies(m -> assertThat(m.papel()).isEqualTo("SERVIDOR_CENTRAL"));
        assertThat(f.acessos()).singleElement().satisfies(a -> {
            assertThat(a.tipo()).isEqualTo("ANYDESK");
            assertThat(a.maquinaId()).isEqualTo(servidor);
            assertThat(a.temSenha()).isFalse();
        });

        assertThat(fichas.buscar("tubarao", null)).extracting(Ficha.Resumo::nome).containsExactly("MH Modas - Tubarão");
        assertThat(fichas.buscar("jorge", null)).extracting(Ficha.Resumo::nome).containsExactly("MH Modas - Urussanga");
        assertThat(fichas.buscar("344788178", null)).extracting(Ficha.Resumo::nome).containsExactly("MH Modas - Urussanga");
        assertThat(fichas.buscar("mh", null)).hasSize(2);
        assertThat(fichas.buscar("100%_", null)).isEmpty();
        // em ordem de grupo e nome: Tubarão, depois Urussanga
        assertThat(fichas.buscar("", null)).extracting(Ficha.Resumo::nome)
                .containsExactly("MH Modas - Tubarão", "MH Modas - Urussanga");
        assertThat(fichas.buscar("", null)).last().satisfies(r -> {
            assertThat(r.alertas()).isEqualTo(1);
            assertThat(r.tiposDeAcesso()).containsExactly("ANYDESK");
        });

        fichas.alterar(suporte, id, new Ficha.PedidoCliente("MH Modas - Urussanga", "MH", null, null, false, "544", null));
        assertThat(fichas.buscar("urussanga", "ativos")).isEmpty();
        assertThat(fichas.buscar("urussanga", "inativos")).hasSize(1);
    }

    @Test
    @DisplayName("Regras: campo obrigatório, tamanho, tipo, porta e máquina de outro cliente")
    void regras() {
        UUID a = fichas.criar(suporte, new Ficha.PedidoCliente("Cliente A", null, null, null, null, null, null));
        UUID b = fichas.criar(suporte, new Ficha.PedidoCliente("Cliente B", null, null, null, null, null, null));
        UUID maquinaDeB = fichas.criarMaquina(suporte, b, maquina("Caixa", "CAIXA", null, null));
        assertCodigo(() -> fichas.criar(suporte, new Ficha.PedidoCliente(" ", null, null, null, null, null, null)),
                "CAMPO_OBRIGATORIO");
        assertCodigo(() -> fichas.criar(suporte, new Ficha.PedidoCliente("x".repeat(161), null, null, null, null, null,
                null)), "CAMPO_GRANDE_DEMAIS");
        assertCodigo(() -> fichas.criarAcesso(suporte, a, acesso(null, "telepatia", "1", null, null,
                null)), "VALOR_INVALIDO");
        assertCodigo(() -> fichas.criarAcesso(suporte, a, acesso(null, "RDP", "10.0.0.1", 70000, null,
                null)), "PORTA_INVALIDA");
        assertCodigo(() -> fichas.criarAcesso(suporte, a, acesso(maquinaDeB, "RDP", "10.0.0.1", null,
                null, null)), "MAQUINA_DE_OUTRO_CLIENTE");
        // item de outro cliente não é alterado nem apagado por este
        assertThatThrownBy(() -> fichas.apagarMaquina(suporte, a, maquinaDeB))
                .hasFieldOrPropertyWithValue("codigo", "ITEM_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("Permissões: ver não edita; editar não apaga cliente nem acesso com senha; anotação só do autor")
    void permissoes() {
        Usuario soVer = new Usuario(UUID.randomUUID(), "visita", "Visita", "x", true,
                new Acesso(List.of(), Set.of(PermissoesDosClientes.VER)), null);
        assertThatThrownBy(() -> fichas.criar(soVer, new Ficha.PedidoCliente("X", null, null, null, null, null, null)))
                .isInstanceOf(AcessoNegadoException.class);
        UUID id = fichas.criar(semSenhas, new Ficha.PedidoCliente("X", null, null, null, null, null, null));
        UUID acesso = fichas.criarAcesso(semSenhas, id, acesso(null, "RADMIN", "26.1.2.3", 54888,
                null, null));
        assertThatThrownBy(() -> fichas.apagarAcesso(semSenhas, id, acesso)).isInstanceOf(AcessoNegadoException.class);
        assertThatThrownBy(() -> fichas.apagar(semSenhas, id)).isInstanceOf(AcessoNegadoException.class);
        UUID nota = fichas.criarAnotacao(suporte, id, new Ficha.PedidoAnotacao("nota do suporte"));
        assertThatThrownBy(() -> fichas.apagarAnotacao(semSenhas, id, nota)).isInstanceOf(AcessoNegadoException.class);
        fichas.apagarAnotacao(admin, id, nota);
        fichas.apagar(admin, id);
        assertThat(fichas.buscar("", "todos")).isEmpty();
    }

    @Test
    @DisplayName("Cofre: autenticador, abrir, mostrar/copiar com registro; a senha nunca aparece na ficha nem na busca")
    void cofre() throws Exception {
        UUID cliente = fichas.criar(suporte, new Ficha.PedidoCliente("Super M", null, null, null, null, null, null));
        UUID acesso = fichas.criarAcesso(suporte, cliente, acesso(null, "ANYDESK", "681337782", null,
                null, null));

        // sem o cofre aberto, nada
        assertCodigo(() -> cofre.senha(suporte, null, AlvoDoCofre.acesso(acesso, null), "MOSTRAR", "26.1.1.1"), "COFRE_FECHADO");
        assertCodigo(() -> cofre.abrir(suporte, "123456", "26.1.1.1"), "SEM_AUTENTICADOR");

        String token = ativar(suporte);
        cofre.gravarSenha(suporte, token, AlvoDoCofre.acesso(acesso, null), "S3nh@-do-cliente", "26.1.1.1");
        assertThat(cofre.senha(suporte, token, AlvoDoCofre.acesso(acesso, null), "COPIAR", "26.1.1.1")).isEqualTo("S3nh@-do-cliente");
        assertThat(cofre.senha(suporte, token, AlvoDoCofre.acesso(acesso, null), "MOSTRAR", "26.1.1.1")).isEqualTo("S3nh@-do-cliente");

        // a ficha e a busca, como a API as devolve, não têm a senha nem o valor cifrado
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        String ficha = json.writeValueAsString(fichas.ficha(cliente));
        String busca = json.writeValueAsString(fichas.buscar("", null));
        assertThat(ficha + busca).doesNotContain("S3nh@").doesNotContain("senha_cifrada").doesNotContain("cifrad");
        assertThat(fichas.ficha(cliente).acessos()).singleElement()
                .satisfies(a -> assertThat(a.temSenha()).isTrue());
        String noBanco = PostgresDeTeste.jdbc().sql("SELECT encode(senha_cifrada, 'escape') FROM clientes.acesso")
                .query(String.class).single();
        assertThat(noBanco).doesNotContain("S3nh@");

        // o token é da pessoa: outra pessoa com ele não abre
        assertCodigo(() -> cofre.senha(admin, token, AlvoDoCofre.acesso(acesso, null), "MOSTRAR", "26.1.1.2"), "COFRE_FECHADO");
        // quem não pode usar senhas nem com token
        assertThatThrownBy(() -> cofre.senha(semSenhas, token, AlvoDoCofre.acesso(acesso, null), "MOSTRAR", "26.1.1.3"))
                .isInstanceOf(AcessoNegadoException.class).hasFieldOrPropertyWithValue("codigo", "SEM_PERMISSAO");

        // passado o tempo, fecha sozinho
        relogio.andar(Duration.ofMinutes(11));
        assertCodigo(() -> cofre.senha(suporte, token, AlvoDoCofre.acesso(acesso, null), "MOSTRAR", "26.1.1.1"), "COFRE_FECHADO");

        List<RegistroDoCofre.Uso> usos = cofre.usos(admin, cliente, 50);
        assertThat(usos).extracting(RegistroDoCofre.Uso::acao).containsExactly("MOSTROU", "COPIOU", "ALTEROU");
        assertThat(usos).allSatisfy(u -> {
            assertThat(u.login()).isEqualTo("suporte");
            assertThat(u.ip()).isEqualTo("26.1.1.1");
            assertThat(u.cliente()).isEqualTo("Super M");
        });
        assertThat(cofre.usos(admin, null, 50)).extracting(RegistroDoCofre.Uso::acao)
                .contains("ABRIU_COFRE", "ATIVOU_AUTENTICADOR");
        assertThatThrownBy(() -> cofre.usos(suporte, null, 50)).isInstanceOf(AcessoNegadoException.class);
    }

    @Test
    @DisplayName("Código repetido não abre; códigos errados demais bloqueiam; zerar o autenticador fecha as sessões")
    void tentativasEReplay() {
        byte[] segredo = segredoDe(cofre.novoAutenticador(suporte));
        String primeiro = Totp.codigo(segredo, Totp.passo(relogio.instant()));
        String token = cofre.ativar(suporte, primeiro, "ip").token();
        // o mesmo código de novo (replay), no mesmo passo
        assertCodigo(() -> cofre.abrir(suporte, primeiro, "ip"), "CODIGO_ERRADO");
        // no passo seguinte, vale
        relogio.andar(Duration.ofSeconds(30));
        cofre.abrir(suporte, Totp.codigo(segredo, Totp.passo(relogio.instant())), "ip");

        for (int i = 0; i < 4; i++) {
            assertCodigo(() -> cofre.abrir(suporte, "000000", "ip"), "CODIGO_ERRADO");
        }
        assertThatThrownBy(() -> cofre.abrir(suporte, "000000", "ip")).isInstanceOf(RegraNegocioException.class);
        String certo = Totp.codigo(segredo, Totp.passo(relogio.instant()) + 1);
        assertThatThrownBy(() -> cofre.abrir(suporte, certo, "ip")).isInstanceOf(MuitasTentativasException.class);
        relogio.andar(Duration.ofMinutes(16));
        cofre.abrir(suporte, Totp.codigo(segredo, Totp.passo(relogio.instant())), "ip");

        // celular perdido: quem administra zera; as sessões abertas fecham e precisa ativar de novo
        assertThat(cofre.autenticadores(admin)).extracting(RegistroDoCofre.AutenticadorDaPessoa::login)
                .containsExactly("suporte");
        cofre.zerarAutenticador(admin, suporte.id(), "ip");
        assertThat(cofre.estado(suporte, token).aberto()).isFalse();
        assertThat(cofre.estado(suporte, null).autenticadorAtivo()).isFalse();
    }

    @Test
    @DisplayName("Autenticador ativo não é trocado por um novo (só zerado por quem administra)")
    void autenticadorAtivoNaoTroca() {
        ativar(suporte);
        assertThatThrownBy(() -> cofre.novoAutenticador(suporte)).hasFieldOrPropertyWithValue("codigo",
                "AUTENTICADOR_JA_ATIVO");
    }

    @Test
    @DisplayName("Frase de recuperação: só com o cofre aberto, por quem administra, com tamanho mínimo")
    void frase() {
        assertThatThrownBy(() -> cofre.definirFraseDeRecuperacao(suporte, null, "uma frase bem comprida", "ip"))
                .isInstanceOf(AcessoNegadoException.class);
        assertCodigo(() -> cofre.definirFraseDeRecuperacao(admin, null, "uma frase bem comprida", "ip"),
                "COFRE_FECHADO");
        String token = ativar(admin);
        assertCodigo(() -> cofre.definirFraseDeRecuperacao(admin, token, "curta", "ip"), "FRASE_FRACA");
        cofre.definirFraseDeRecuperacao(admin, token, "uma frase bem comprida", "ip");
        assertThat(cofre.estado(admin, token).recuperacaoDefinida()).isTrue();
        assertCodigo(() -> cofre.restaurarChave(admin, "uma frase bem comprida", "ip"), "COFRE_NAO_TRANCADO");
    }

    @Test
    @DisplayName("Radmin com rede: duas senhas separadas; uma não abre no lugar da outra")
    void senhaDaRede() {
        UUID cliente = fichas.criar(suporte, new Ficha.PedidoCliente("Bolichão", null, null, null, null, null, null));
        UUID radmin = fichas.criarAcesso(suporte, cliente, new Ficha.PedidoAcesso(null, "RADMIN", "26.7.210.169", null,
                "bolichao-rede", "Administrador", null));
        String token = ativar(suporte);
        cofre.gravarSenha(suporte, token, AlvoDoCofre.acesso(radmin, "rede"), "senha-da-rede", "ip");
        cofre.gravarSenha(suporte, token, AlvoDoCofre.acesso(radmin, null), "senha-do-windows", "ip");

        assertThat(cofre.senha(suporte, token, AlvoDoCofre.acesso(radmin, "rede"), "MOSTRAR", "ip"))
                .isEqualTo("senha-da-rede");
        assertThat(cofre.senha(suporte, token, AlvoDoCofre.acesso(radmin, null), "MOSTRAR", "ip"))
                .isEqualTo("senha-do-windows");
        assertThat(fichas.ficha(cliente).acessos()).singleElement().satisfies(a -> {
            assertThat(a.rede()).isEqualTo("bolichao-rede");
            assertThat(a.temSenhaDaRede()).isTrue();
            assertThat(a.temSenha()).isTrue();
            assertThat(a.senhaDaRedeAlteradaPor()).isEqualTo("suporte");
        });
        assertThat(fichas.buscar("bolichao-rede", null)).hasSize(1);

        // a senha da rede copiada para a coluna da senha da máquina não abre (o dono da cifra é outro)
        PostgresDeTeste.jdbc().sql("""
                        UPDATE clientes.acesso SET senha_cifrada = rede_senha_cifrada, senha_vetor = rede_senha_vetor
                         WHERE id = :id""").param("id", radmin).update();
        assertCodigo(() -> cofre.senha(suporte, token, AlvoDoCofre.acesso(radmin, null), "MOSTRAR", "ip"),
                "COFRE_NAO_ABRE");
        cofre.gravarSenha(suporte, token, AlvoDoCofre.acesso(radmin, null), "senha-do-windows", "ip");

        // apagar a senha da rede deixa a outra
        cofre.gravarSenha(suporte, token, AlvoDoCofre.acesso(radmin, "rede"), "", "ip");
        assertThat(fichas.ficha(cliente).acessos()).singleElement().satisfies(a -> {
            assertThat(a.temSenhaDaRede()).isFalse();
            assertThat(a.temSenha()).isTrue();
        });
        assertThat(cofre.usos(admin, cliente, 50)).extracting(RegistroDoCofre.Uso::descricao)
                .contains("Bolichão · RADMIN 26.7.210.169 (rede bolichao-rede)");
    }

    @Test
    @DisplayName("Usuários do cliente: cadastro, senha no cofre, auditoria, busca e permissões")
    void usuariosDoCliente() throws Exception {
        UUID cliente = fichas.criar(suporte, new Ficha.PedidoCliente("Loja X", null, null, null, null, null, null));
        UUID outro = fichas.criar(suporte, new Ficha.PedidoCliente("Loja Y", null, null, null, null, null, null));
        UUID servidor = fichas.criarMaquina(suporte, cliente, new Ficha.PedidoMaquina("Servidor", "SERVIDOR", "SRV-LOJA",
                "192.168.0.10", "26.1.2.3", "Windows Server 2019", "C:\\sislojas", "Firebird 2.5",
                "C:\\sislojas\\dados\\BASE.FDB", "Xeon E3", "16 GB", "SSD 480 GB", "D:\\backup", "Fica no estoque"));
        UUID maquinaDeOutro = fichas.criarMaquina(suporte, outro, maquina("Caixa", "CAIXA", null, null));

        UUID sistema = fichas.criarCredencial(suporte, cliente, new Ficha.PedidoCredencial(null, "sistema", "Sislojas",
                "gerente", "Usuário do gerente"));
        UUID banco = fichas.criarCredencial(suporte, cliente, new Ficha.PedidoCredencial(servidor, "BANCO", "Firebird",
                "SYSDBA", null));
        assertCodigo(() -> fichas.criarCredencial(suporte, cliente, new Ficha.PedidoCredencial(null, "telepatia", null,
                "x", null)), "VALOR_INVALIDO");
        assertCodigo(() -> fichas.criarCredencial(suporte, cliente, new Ficha.PedidoCredencial(null, "WINDOWS", null,
                " ", null)), "CAMPO_OBRIGATORIO");
        assertCodigo(() -> fichas.criarCredencial(suporte, cliente, new Ficha.PedidoCredencial(maquinaDeOutro, "WINDOWS",
                null, "caixa", null)), "MAQUINA_DE_OUTRO_CLIENTE");

        String token = ativar(suporte);
        cofre.gravarSenha(suporte, token, AlvoDoCofre.credencial(banco), "masterkey-xyz", "ip");
        assertThat(cofre.senha(suporte, token, AlvoDoCofre.credencial(banco), "COPIAR", "ip")).isEqualTo("masterkey-xyz");
        assertCodigo(() -> cofre.senha(suporte, token, AlvoDoCofre.credencial(sistema), "MOSTRAR", "ip"), "SEM_SENHA");
        assertCodigo(() -> cofre.senha(suporte, token, AlvoDoCofre.credencial(UUID.randomUUID()), "MOSTRAR", "ip"),
                "USUARIO_NAO_ENCONTRADO");
        // o id de um usuário não serve como id de acesso
        assertCodigo(() -> cofre.senha(suporte, token, AlvoDoCofre.acesso(banco, null), "MOSTRAR", "ip"),
                "ACESSO_NAO_ENCONTRADO");

        Ficha.Completa f = fichas.ficha(cliente);
        assertThat(f.credenciais()).extracting(Ficha.Credencial::onde).containsExactly("SISTEMA", "BANCO");
        assertThat(f.credenciais()).filteredOn(c -> c.id().equals(banco)).singleElement().satisfies(c -> {
            assertThat(c.temSenha()).isTrue();
            assertThat(c.maquinaId()).isEqualTo(servidor);
        });
        assertThat(f.maquinas()).singleElement().satisfies(m -> {
            assertThat(m.nomeComputador()).isEqualTo("SRV-LOJA");
            assertThat(m.bancoDados()).isEqualTo("Firebird 2.5");
            assertThat(m.localBackup()).isEqualTo("D:\\backup");
        });
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        assertThat(json.writeValueAsString(f)).doesNotContain("masterkey").doesNotContain("cifrad");
        assertThat(fichas.buscar("sysdba", null)).extracting(Ficha.Resumo::nome).containsExactly("Loja X");
        assertThat(fichas.buscar("srv-loja", null)).extracting(Ficha.Resumo::nome).containsExactly("Loja X");

        List<RegistroDoCofre.Uso> usos = cofre.usos(admin, cliente, 50);
        assertThat(usos).extracting(RegistroDoCofre.Uso::acao).containsExactly("COPIOU", "ALTEROU");
        assertThat(usos).allSatisfy(u -> {
            assertThat(u.credencialId()).isEqualTo(banco);
            assertThat(u.acessoId()).isNull();
            assertThat(u.descricao()).isEqualTo("Loja X · BANCO Firebird SYSDBA");
        });

        // quem não usa senhas não apaga (a senha iria junto); quem usa, apaga
        assertThatThrownBy(() -> fichas.apagarCredencial(semSenhas, cliente, banco))
                .isInstanceOf(AcessoNegadoException.class);
        assertThatThrownBy(() -> fichas.apagarCredencial(suporte, outro, banco))
                .hasFieldOrPropertyWithValue("codigo", "ITEM_NAO_ENCONTRADO");
        fichas.apagarCredencial(suporte, cliente, banco);
        assertThat(fichas.ficha(cliente).credenciais()).extracting(Ficha.Credencial::id).containsExactly(sistema);
        // a máquina apagada solta o usuário, que continua na ficha
        fichas.alterarCredencial(suporte, cliente, sistema, new Ficha.PedidoCredencial(servidor, "SISTEMA", "Sislojas",
                "gerente", null));
        fichas.apagarMaquina(suporte, cliente, servidor);
        assertThat(fichas.ficha(cliente).credenciais()).singleElement()
                .satisfies(c -> assertThat(c.maquinaId()).isNull());
    }

    // ------------------------------------------------------------------------------------------ auxiliares

    private static Ficha.PedidoMaquina maquina(String nome, String papel, String pasta, String observacao) {
        return new Ficha.PedidoMaquina(nome, papel, null, null, null, null, pasta, null, null, null, null, null, null,
                observacao);
    }

    private static Ficha.PedidoAcesso acesso(UUID maquina, String tipo, String identificador, Integer porta,
                                             String usuario, String observacao) {
        return new Ficha.PedidoAcesso(maquina, tipo, identificador, porta, null, usuario, observacao);
    }

    private String ativar(Usuario quem) {
        byte[] segredo = segredoDe(cofre.novoAutenticador(quem));
        return cofre.ativar(quem, Totp.codigo(segredo, Totp.passo(relogio.instant())), "ip").token();
    }

    private static byte[] segredoDe(CofreDeAcessos.NovoAutenticador novo) {
        assertThat(novo.endereco()).startsWith("otpauth://totp/Plumb:");
        return Base32.decodificar(novo.segredo());
    }

    private static Usuario usuario(org.springframework.jdbc.core.simple.JdbcClient sql, String login,
                                   String... permissoes) {
        UUID id = sql.sql("""
                        INSERT INTO tb_usuario (login, nome, senha_hash) VALUES (:login, :login, 'x')
                        ON CONFLICT (login) DO UPDATE SET nome = EXCLUDED.nome RETURNING id""")
                .param("login", login).query(UUID.class).single();
        return new Usuario(id, login, login, "x", true, new Acesso(List.of(), Set.of(permissoes)), null);
    }

    private static void assertCodigo(org.assertj.core.api.ThrowableAssert.ThrowingCallable acao, String codigo) {
        assertThatThrownBy(acao).hasFieldOrPropertyWithValue("codigo", codigo);
    }

    static final class Relogio extends Clock {
        private Instant agora;

        Relogio(Instant inicio) {
            this.agora = inicio;
        }

        void andar(Duration quanto) {
            agora = agora.plus(quanto);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora;
        }
    }
}
