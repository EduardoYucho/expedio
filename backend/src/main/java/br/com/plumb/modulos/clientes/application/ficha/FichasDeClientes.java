package br.com.plumb.modulos.clientes.application.ficha;

import br.com.plumb.domain.exception.AcessoNegadoException;
import br.com.plumb.domain.exception.RecursoNaoEncontradoException;
import br.com.plumb.domain.exception.RegraNegocioException;
import br.com.plumb.domain.model.Usuario;
import br.com.plumb.modulos.clientes.application.acesso.PermissoesDosClientes;
import br.com.plumb.modulos.clientes.domain.PapelDaMaquina;
import br.com.plumb.modulos.clientes.domain.TipoDeAcesso;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A ficha dos clientes: busca, leitura e as alterações (com as regras de cada campo). Ler exige {@code clientes.ver}
 * (conferido antes, nas rotas); gravar exige {@code clientes.editar}; apagar o cliente, {@code clientes.administrar}.
 */
@Service
public class FichasDeClientes {

    static final int LIMITE_DA_BUSCA = 300;

    private final CadastroDeClientes cadastro;

    public FichasDeClientes(CadastroDeClientes cadastro) {
        this.cadastro = cadastro;
    }

    public List<Ficha.Resumo> buscar(String termo, String situacao) {
        String s = situacao == null ? "ativos" : situacao.toLowerCase(Locale.ROOT);
        if (!List.of("ativos", "inativos", "todos").contains(s)) {
            throw new RegraNegocioException("SITUACAO_INVALIDA", "Situação deve ser ativos, inativos ou todos.");
        }
        String t = termo == null ? "" : semAcento(termo.strip().replaceAll("\\s+", " ")).toLowerCase(Locale.ROOT);
        if (t.length() > 100) {
            t = t.substring(0, 100);
        }
        return cadastro.buscar(t, s, LIMITE_DA_BUSCA);
    }

    public List<String> grupos() {
        return cadastro.grupos();
    }

    public Ficha.Completa ficha(UUID id) {
        return cadastro.ficha(id).orElseThrow(FichasDeClientes::naoEncontrado);
    }

    // ------------------------------------------------------------------------------------------ cliente

    public UUID criar(Usuario quem, Ficha.PedidoCliente pedido) {
        exigir(quem, PermissoesDosClientes.EDITAR);
        return cadastro.criar(normalizar(pedido), quem.login());
    }

    public void alterar(Usuario quem, UUID id, Ficha.PedidoCliente pedido) {
        exigir(quem, PermissoesDosClientes.EDITAR);
        existir(id);
        cadastro.alterar(id, normalizar(pedido), quem.login());
    }

    public void apagar(Usuario quem, UUID id) {
        exigir(quem, PermissoesDosClientes.ADMINISTRAR);
        existir(id);
        cadastro.apagar(id);
    }

    // ------------------------------------------------------------------------------------------ itens da ficha

    public UUID criarAlerta(Usuario quem, UUID cliente, Ficha.PedidoAlerta p) {
        exigir(quem, PermissoesDosClientes.EDITAR);
        existir(cliente);
        return cadastro.criarAlerta(cliente, alerta(p), quem.login());
    }

    public void alterarAlerta(Usuario quem, UUID cliente, UUID id, Ficha.PedidoAlerta p) {
        exigir(quem, PermissoesDosClientes.EDITAR);
        achou(cadastro.alterarAlerta(cliente, id, alerta(p)));
    }

    public void apagarAlerta(Usuario quem, UUID cliente, UUID id) {
        exigir(quem, PermissoesDosClientes.EDITAR);
        achou(cadastro.apagarAlerta(cliente, id));
    }

    public UUID criarContato(Usuario quem, UUID cliente, Ficha.PedidoContato p) {
        exigir(quem, PermissoesDosClientes.EDITAR);
        existir(cliente);
        return cadastro.criarContato(cliente, contato(p));
    }

    public void alterarContato(Usuario quem, UUID cliente, UUID id, Ficha.PedidoContato p) {
        exigir(quem, PermissoesDosClientes.EDITAR);
        achou(cadastro.alterarContato(cliente, id, contato(p)));
    }

    public void apagarContato(Usuario quem, UUID cliente, UUID id) {
        exigir(quem, PermissoesDosClientes.EDITAR);
        achou(cadastro.apagarContato(cliente, id));
    }

    public UUID criarMaquina(Usuario quem, UUID cliente, Ficha.PedidoMaquina p) {
        exigir(quem, PermissoesDosClientes.EDITAR);
        existir(cliente);
        return cadastro.criarMaquina(cliente, maquina(p));
    }

    public void alterarMaquina(Usuario quem, UUID cliente, UUID id, Ficha.PedidoMaquina p) {
        exigir(quem, PermissoesDosClientes.EDITAR);
        achou(cadastro.alterarMaquina(cliente, id, maquina(p)));
    }

    public void apagarMaquina(Usuario quem, UUID cliente, UUID id) {
        exigir(quem, PermissoesDosClientes.EDITAR);
        achou(cadastro.apagarMaquina(cliente, id));
    }

    public UUID criarAcesso(Usuario quem, UUID cliente, Ficha.PedidoAcesso p) {
        exigir(quem, PermissoesDosClientes.EDITAR);
        existir(cliente);
        return cadastro.criarAcesso(cliente, acesso(cliente, p), quem.login());
    }

    public void alterarAcesso(Usuario quem, UUID cliente, UUID id, Ficha.PedidoAcesso p) {
        exigir(quem, PermissoesDosClientes.EDITAR);
        achou(cadastro.alterarAcesso(cliente, id, acesso(cliente, p), quem.login()));
    }

    /** Apagar um acesso apaga a senha dele junto: exige poder usar as senhas (ou administrar). */
    public void apagarAcesso(Usuario quem, UUID cliente, UUID id) {
        exigir(quem, PermissoesDosClientes.EDITAR);
        if (!quem.pode(PermissoesDosClientes.SENHAS) && !quem.pode(PermissoesDosClientes.ADMINISTRAR)) {
            throw new AcessoNegadoException("SEM_PERMISSAO",
                    "Apagar um acesso apaga a senha dele: é só para quem usa as senhas dos clientes.");
        }
        achou(cadastro.apagarAcesso(cliente, id));
    }

    public UUID criarAnotacao(Usuario quem, UUID cliente, Ficha.PedidoAnotacao p) {
        exigir(quem, PermissoesDosClientes.EDITAR);
        existir(cliente);
        String texto = texto(p == null ? null : p.texto(), 4000, "A anotação", true);
        return cadastro.criarAnotacao(cliente, texto, quem.login());
    }

    /** Só quem escreveu (ou quem administra) apaga uma anotação: o histórico não some por engano. */
    public void apagarAnotacao(Usuario quem, UUID cliente, UUID id) {
        exigir(quem, PermissoesDosClientes.EDITAR);
        String autor = cadastro.autorDaAnotacao(cliente, id).orElseThrow(FichasDeClientes::naoEncontrado);
        if (!autor.equals(quem.login()) && !quem.pode(PermissoesDosClientes.ADMINISTRAR)) {
            throw new AcessoNegadoException("SEM_PERMISSAO", "Só quem escreveu a anotação pode apagá-la.");
        }
        achou(cadastro.apagarAnotacao(cliente, id));
    }

    // ------------------------------------------------------------------------------------------ regras dos campos

    private Ficha.PedidoCliente normalizar(Ficha.PedidoCliente p) {
        if (p == null) {
            throw new RegraNegocioException("PEDIDO_VAZIO", "Informe os dados do cliente.");
        }
        return new Ficha.PedidoCliente(
                texto(p.nome(), 160, "O nome", true),
                texto(p.grupo(), 120, "O grupo", false),
                texto(p.documento(), 20, "O CNPJ/CPF", false),
                texto(p.cidade(), 80, "A cidade", false),
                p.ativo() == null || p.ativo(),
                texto(p.codigoChamados(), 20, "O código no sistema de chamados", false),
                texto(p.observacao(), 4000, "A observação", false));
    }

    private static Ficha.PedidoAlerta alerta(Ficha.PedidoAlerta p) {
        return new Ficha.PedidoAlerta(texto(p == null ? null : p.texto(), 500, "O alerta", true),
                p == null || p.ativo() == null || p.ativo());
    }

    private static Ficha.PedidoContato contato(Ficha.PedidoContato p) {
        if (p == null) {
            throw new RegraNegocioException("PEDIDO_VAZIO", "Informe o contato.");
        }
        return new Ficha.PedidoContato(texto(p.nome(), 120, "O nome do contato", true),
                texto(p.funcao(), 80, "A função", false), texto(p.telefone(), 40, "O telefone", false),
                texto(p.email(), 160, "O e-mail", false), p.autoriza() != null && p.autoriza(),
                texto(p.observacao(), 300, "A observação", false));
    }

    private static Ficha.PedidoMaquina maquina(Ficha.PedidoMaquina p) {
        if (p == null) {
            throw new RegraNegocioException("PEDIDO_VAZIO", "Informe a máquina.");
        }
        return new Ficha.PedidoMaquina(texto(p.nome(), 80, "O nome da máquina", true),
                enumerado(PapelDaMaquina.class, p.papel(), PapelDaMaquina.OUTRA, "O papel da máquina").name(),
                texto(p.pastaSistema(), 260, "A pasta do sistema", false),
                texto(p.observacao(), 500, "A observação", false));
    }

    private Ficha.PedidoAcesso acesso(UUID cliente, Ficha.PedidoAcesso p) {
        if (p == null) {
            throw new RegraNegocioException("PEDIDO_VAZIO", "Informe o acesso.");
        }
        if (p.maquinaId() != null && !cadastro.maquinaDoCliente(cliente, p.maquinaId())) {
            throw new RegraNegocioException("MAQUINA_DE_OUTRO_CLIENTE", "A máquina escolhida não é deste cliente.");
        }
        if (p.porta() != null && (p.porta() < 1 || p.porta() > 65535)) {
            throw new RegraNegocioException("PORTA_INVALIDA", "A porta deve estar entre 1 e 65535.");
        }
        return new Ficha.PedidoAcesso(p.maquinaId(),
                enumerado(TipoDeAcesso.class, p.tipo(), null, "O tipo do acesso").name(),
                texto(p.identificador(), 200, "O ID ou endereço", true), p.porta(),
                texto(p.usuario(), 120, "O usuário", false), texto(p.observacao(), 500, "A observação", false));
    }

    static String texto(String valor, int maximo, String campo, boolean obrigatorio) {
        String limpo = valor == null ? null : valor.strip();
        if (limpo == null || limpo.isEmpty()) {
            if (obrigatorio) {
                throw new RegraNegocioException("CAMPO_OBRIGATORIO", campo + " é obrigatório.");
            }
            return null;
        }
        if (limpo.length() > maximo) {
            throw new RegraNegocioException("CAMPO_GRANDE_DEMAIS",
                    "%s pode ter até %d caracteres.".formatted(campo, maximo));
        }
        return limpo;
    }

    private static <E extends Enum<E>> E enumerado(Class<E> tipo, String valor, E padrao, String campo) {
        if (valor == null || valor.isBlank()) {
            if (padrao != null) {
                return padrao;
            }
            throw new RegraNegocioException("CAMPO_OBRIGATORIO", campo + " é obrigatório.");
        }
        try {
            return Enum.valueOf(tipo, valor.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RegraNegocioException("VALOR_INVALIDO", campo + " não é um dos valores aceitos.");
        }
    }

    static String semAcento(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    private void existir(UUID id) {
        if (!cadastro.existe(id)) {
            throw naoEncontrado();
        }
    }

    private static void achou(boolean alterou) {
        if (!alterou) {
            throw new RecursoNaoEncontradoException("ITEM_NAO_ENCONTRADO", "Este item não existe mais nesta ficha.");
        }
    }

    private static void exigir(Usuario quem, String permissao) {
        if (!quem.pode(permissao)) {
            throw new AcessoNegadoException("SEM_PERMISSAO", "Você não tem permissão para isso nos clientes.");
        }
    }

    private static RecursoNaoEncontradoException naoEncontrado() {
        return new RecursoNaoEncontradoException("CLIENTE_NAO_ENCONTRADO", "Este cliente não existe mais.");
    }
}
