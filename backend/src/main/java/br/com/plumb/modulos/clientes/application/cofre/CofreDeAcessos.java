package br.com.plumb.modulos.clientes.application.cofre;

import br.com.plumb.domain.exception.AcessoNegadoException;
import br.com.plumb.domain.exception.ConflitoException;
import br.com.plumb.domain.exception.MuitasTentativasException;
import br.com.plumb.domain.exception.RecursoNaoEncontradoException;
import br.com.plumb.domain.exception.RegraNegocioException;
import br.com.plumb.domain.model.Usuario;
import br.com.plumb.modulos.clientes.application.acesso.PermissoesDosClientes;
import br.com.plumb.modulos.clientes.domain.Base32;
import br.com.plumb.modulos.clientes.domain.Totp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * O cofre das senhas dos clientes.
 * <ul>
 *   <li>Abrir exige {@code clientes.senhas} e o código do app autenticador da própria pessoa: o cofre fica aberto por
 *       alguns minutos ({@code clientes.cofre.minutos-aberto}) com um token que a tela guarda só na memória
 *       (header {@code X-Cofre}). O token vale só para quem abriu; reiniciar o sistema fecha todos.</li>
 *   <li>Código errado ou repetido conta como tentativa: passou do limite, fica bloqueado por um tempo.</li>
 *   <li>Mostrar, copiar, alterar e apagar senha, abrir o cofre e as tarefas de administração ficam registrados.</li>
 * </ul>
 */
@Service
public class CofreDeAcessos {

    private static final Logger log = LoggerFactory.getLogger(CofreDeAcessos.class);
    private static final SecureRandom ALEATORIO = new SecureRandom();
    static final String EMISSOR = "Plumb";
    static final int TAMANHO_MAXIMO_DA_SENHA = 200;
    static final int FRASE_MINIMA = 16;

    /** Quanto tempo o cofre fica aberto e quantas tentativas erradas bloqueiam (e por quanto tempo). */
    public record Parametros(Duration aberto, int tentativas, Duration bloqueio) {
    }

    public record Estado(boolean podeUsarSenhas, boolean administra, boolean autenticadorAtivo,
                         boolean autenticadorPendente, boolean aberto, Instant abertoAte, String chave,
                         boolean recuperacaoDefinida) {
    }

    public record NovoAutenticador(String endereco, String segredo) {
    }

    public record Abertura(String token, Instant abertoAte) {
    }

    private record Sessao(UUID usuarioId, Instant ate) {
    }

    private final ChavesDoCofre chaves;
    private final RegistroDoCofre registro;
    private final Clock relogio;
    private final Parametros parametros;
    private final Map<String, Sessao> sessoes = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> erros = new ConcurrentHashMap<>();

    public CofreDeAcessos(ChavesDoCofre chaves, RegistroDoCofre registro, Clock relogio, Parametros parametros) {
        this.chaves = chaves;
        this.registro = registro;
        this.relogio = relogio;
        this.parametros = parametros;
    }

    public Estado estado(Usuario quem, String token) {
        var autenticador = registro.autenticador(quem.id());
        Sessao sessao = sessaoValida(quem, token);
        return new Estado(quem.pode(PermissoesDosClientes.SENHAS), quem.pode(PermissoesDosClientes.ADMINISTRAR),
                autenticador.map(RegistroDoCofre.Autenticador::ativo).orElse(false),
                autenticador.map(a -> !a.ativo()).orElse(false),
                sessao != null, sessao == null ? null : sessao.ate(), chaves.situacao().name(), chaves.temRecuperacao());
    }

    // ------------------------------------------------------------------------------------------ autenticador

    /** Um segredo novo para o app (o QR code). Só vale depois de confirmado com um código ({@link #ativar}). */
    public NovoAutenticador novoAutenticador(Usuario quem) {
        exigir(quem, PermissoesDosClientes.SENHAS);
        exigirChaveUtilizavel();
        if (registro.autenticador(quem.id()).map(RegistroDoCofre.Autenticador::ativo).orElse(false)) {
            throw new ConflitoException("AUTENTICADOR_JA_ATIVO", "Seu autenticador já está ativo. Se perdeu o "
                    + "celular, peça a quem administra o cofre para zerá-lo.");
        }
        byte[] segredo = new byte[Totp.BYTES_DO_SEGREDO];
        ALEATORIO.nextBytes(segredo);
        String texto = Base32.codificar(segredo);
        registro.novoAutenticador(quem.id(), chaves.cifrar(texto, quem.id()));
        return new NovoAutenticador(Totp.endereco(EMISSOR, quem.login(), segredo), texto.replaceAll("(.{4})", "$1 ").strip());
    }

    /** Confirma o app com o primeiro código e já abre o cofre. */
    public Abertura ativar(Usuario quem, String codigo, String ip) {
        exigir(quem, PermissoesDosClientes.SENHAS);
        exigirChaveUtilizavel();
        String chaveDeErros = "codigo:" + quem.id();
        exigirSemBloqueio(chaveDeErros);
        RegistroDoCofre.Autenticador pendente = registro.autenticador(quem.id()).filter(a -> !a.ativo())
                .orElseThrow(() -> new RegraNegocioException("SEM_AUTENTICADOR_PENDENTE",
                        "Gere o QR code de novo e leia com o app antes de confirmar."));
        Instant agora = relogio.instant();
        long passo = Totp.conferir(segredo(pendente), codigo, agora, -1);
        if (passo < 0) {
            falhou(chaveDeErros, quem, ip);
        }
        registro.ativarAutenticador(quem.id(), passo, agora);
        registro.registrarUso(quem.login(), "ATIVOU_AUTENTICADOR", null, null, "Autenticador ativado", ip);
        return abrirSessao(quem, ip);
    }

    // ------------------------------------------------------------------------------------------ abrir e fechar

    public Abertura abrir(Usuario quem, String codigo, String ip) {
        exigir(quem, PermissoesDosClientes.SENHAS);
        exigirChaveUtilizavel();
        String chaveDeErros = "codigo:" + quem.id();
        exigirSemBloqueio(chaveDeErros);
        RegistroDoCofre.Autenticador autenticador = registro.autenticador(quem.id())
                .filter(RegistroDoCofre.Autenticador::ativo)
                .orElseThrow(() -> new RegraNegocioException("SEM_AUTENTICADOR",
                        "Ative o autenticador do celular antes de abrir o cofre."));
        long passo = Totp.conferir(segredo(autenticador), codigo, relogio.instant(), autenticador.ultimoPasso());
        if (passo < 0 || !registro.usarPasso(quem.id(), passo)) {
            falhou(chaveDeErros, quem, ip);
        }
        return abrirSessao(quem, ip);
    }

    public void fechar(String token) {
        if (token != null) {
            sessoes.remove(token);
        }
    }

    // ------------------------------------------------------------------------------------------ senhas

    /** Uma senha (do acesso, da rede do acesso ou de um usuário do cliente) para mostrar ou copiar, com registro. */
    public String senha(Usuario quem, String token, AlvoDoCofre alvo, String acao, String ip) {
        exigirAberto(quem, token);
        String registrada = "COPIAR".equalsIgnoreCase(acao) ? "COPIOU" : "MOSTROU";
        RegistroDoCofre.SenhaGuardada guardada = registro.senha(alvo).orElseThrow(() -> semAlvo(alvo));
        if (guardada.senha() == null) {
            throw new RegraNegocioException("SEM_SENHA", "Não há senha guardada aqui.");
        }
        String senha = chaves.decifrar(guardada.senha(), alvo.dono());
        registro.registrarUso(quem.login(), registrada, guardada.clienteId(), alvo, guardada.descricao(), ip);
        return senha;
    }

    /** Grava (ou, vazia, apaga) uma senha. */
    public void gravarSenha(Usuario quem, String token, AlvoDoCofre alvo, String senha, String ip) {
        exigirAberto(quem, token);
        if (senha != null && senha.length() > TAMANHO_MAXIMO_DA_SENHA) {
            throw new RegraNegocioException("SENHA_GRANDE_DEMAIS",
                    "A senha pode ter até %d caracteres.".formatted(TAMANHO_MAXIMO_DA_SENHA));
        }
        RegistroDoCofre.SenhaGuardada guardada = registro.senha(alvo).orElseThrow(() -> semAlvo(alvo));
        boolean apagar = senha == null || senha.isEmpty();
        registro.gravarSenha(alvo, apagar ? null : chaves.cifrar(senha, alvo.dono()), quem.login(), relogio.instant());
        registro.registrarUso(quem.login(), apagar ? "APAGOU" : "ALTEROU", guardada.clienteId(), alvo,
                guardada.descricao(), ip);
    }

    // ------------------------------------------------------------------------------------------ administração

    public List<RegistroDoCofre.AutenticadorDaPessoa> autenticadores(Usuario quem) {
        exigir(quem, PermissoesDosClientes.ADMINISTRAR);
        return registro.autenticadores();
    }

    /** Celular perdido ou trocado: a pessoa ativa de novo (e as sessões abertas dela fecham). */
    public void zerarAutenticador(Usuario quem, UUID alvo, String ip) {
        exigir(quem, PermissoesDosClientes.ADMINISTRAR);
        String nome = registro.autenticadores().stream().filter(a -> a.usuarioId().equals(alvo))
                .map(RegistroDoCofre.AutenticadorDaPessoa::login).findFirst()
                .orElseThrow(() -> new RecursoNaoEncontradoException("SEM_AUTENTICADOR",
                        "Esta pessoa não tem autenticador."));
        registro.apagarAutenticador(alvo);
        sessoes.values().removeIf(s -> s.usuarioId().equals(alvo));
        registro.registrarUso(quem.login(), "ZEROU_AUTENTICADOR", null, null, "Autenticador de " + nome, ip);
    }

    public List<RegistroDoCofre.Uso> usos(Usuario quem, UUID cliente, int limite) {
        exigir(quem, PermissoesDosClientes.ADMINISTRAR);
        return registro.usos(cliente, Math.max(1, Math.min(limite, 1000)));
    }

    /** A frase que recupera a chave do cofre num computador novo (a cópia embrulhada é a que vai para o backup). */
    public void definirFraseDeRecuperacao(Usuario quem, String token, String frase, String ip) {
        exigir(quem, PermissoesDosClientes.ADMINISTRAR);
        exigirAberto(quem, token);
        if (frase == null || frase.strip().length() < FRASE_MINIMA || frase.length() > 300) {
            throw new RegraNegocioException("FRASE_FRACA", "A frase precisa ter pelo menos %d caracteres "
                    .formatted(FRASE_MINIMA) + "(use 4 ou mais palavras que só você saiba).");
        }
        chaves.definirFraseDeRecuperacao(frase);
        registro.registrarUso(quem.login(), "DEFINIU_RECUPERACAO", null, null, "Frase de recuperação definida", ip);
    }

    /** Cofre trancado (a chave sumiu, por exemplo num computador novo): a frase recria a chave. */
    public void restaurarChave(Usuario quem, String frase, String ip) {
        exigir(quem, PermissoesDosClientes.ADMINISTRAR);
        if (chaves.situacao() != ChavesDoCofre.Situacao.TRANCADA) {
            throw new ConflitoException("COFRE_NAO_TRANCADO", "O cofre não está trancado: não há o que restaurar.");
        }
        String chaveDeErros = "frase";
        exigirSemBloqueio(chaveDeErros);
        try {
            chaves.restaurarComFrase(frase == null ? "" : frase);
        } catch (RegraNegocioException e) {
            if ("FRASE_ERRADA".equals(e.getCodigo())) {
                registrarErro(chaveDeErros);
                registro.registrarUso(quem.login(), "FRASE_ERRADA", null, null, "Tentativa de restaurar a chave", ip);
            }
            throw e;
        }
        erros.remove(chaveDeErros);
        registro.registrarUso(quem.login(), "RESTAUROU_CHAVE", null, null, "Chave do cofre restaurada pela frase", ip);
    }

    // ------------------------------------------------------------------------------------------ auxiliares

    private Abertura abrirSessao(Usuario quem, String ip) {
        erros.remove("codigo:" + quem.id());
        Instant agora = relogio.instant();
        sessoes.values().removeIf(s -> !s.ate().isAfter(agora));
        byte[] bytes = new byte[32];
        ALEATORIO.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant ate = agora.plus(parametros.aberto());
        sessoes.put(token, new Sessao(quem.id(), ate));
        registro.registrarUso(quem.login(), "ABRIU_COFRE", null, null, "Cofre aberto", ip);
        return new Abertura(token, ate);
    }

    private Sessao sessaoValida(Usuario quem, String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Sessao sessao = sessoes.get(token);
        if (sessao == null || !sessao.usuarioId().equals(quem.id()) || !sessao.ate().isAfter(relogio.instant())) {
            return null;
        }
        return sessao;
    }

    private void exigirAberto(Usuario quem, String token) {
        exigir(quem, PermissoesDosClientes.SENHAS);
        if (sessaoValida(quem, token) == null) {
            throw new AcessoNegadoException("COFRE_FECHADO", "O cofre está fechado: abra com o código do celular.");
        }
    }

    private void exigirChaveUtilizavel() {
        if (chaves.situacao() == ChavesDoCofre.Situacao.TRANCADA) {
            throw new RegraNegocioException("COFRE_TRANCADO", "O cofre está trancado: a chave dele não está neste "
                    + "computador. Quem administra o cofre restaura com a frase de recuperação.");
        }
    }

    private byte[] segredo(RegistroDoCofre.Autenticador autenticador) {
        return Base32.decodificar(chaves.decifrar(autenticador.segredo(), autenticador.usuarioId()));
    }

    private void falhou(String chaveDeErros, Usuario quem, String ip) {
        registrarErro(chaveDeErros);
        registro.registrarUso(quem.login(), "CODIGO_ERRADO", null, null, "Código do autenticador recusado", ip);
        log.warn("Código do autenticador recusado para \"{}\"", quem.login());
        throw new RegraNegocioException("CODIGO_ERRADO",
                "O código não confere (ou já foi usado). Confira a hora do celular e digite o código atual.");
    }

    private void registrarErro(String chave) {
        Instant agora = relogio.instant();
        Deque<Instant> lista = erros.computeIfAbsent(chave, c -> new ArrayDeque<>());
        synchronized (lista) {
            lista.addLast(agora);
            while (lista.size() > parametros.tentativas()) {
                lista.removeFirst();
            }
        }
    }

    private void exigirSemBloqueio(String chave) {
        Deque<Instant> lista = erros.get(chave);
        if (lista == null) {
            return;
        }
        Instant agora = relogio.instant();
        synchronized (lista) {
            lista.removeIf(em -> !em.plus(parametros.bloqueio()).isAfter(agora));
            if (lista.size() >= parametros.tentativas()) {
                Duration espera = Duration.between(agora, lista.peekLast().plus(parametros.bloqueio()));
                throw new MuitasTentativasException("COFRE_BLOQUEADO", "Muitos códigos errados: espere %d minutos."
                        .formatted(Math.max(1, (espera.toSeconds() + 59) / 60)), espera);
            }
        }
    }

    private static void exigir(Usuario quem, String permissao) {
        if (!quem.pode(permissao)) {
            throw new AcessoNegadoException("SEM_PERMISSAO", "Você não tem permissão para isso no cofre dos clientes.");
        }
    }

    private static RecursoNaoEncontradoException semAlvo(AlvoDoCofre alvo) {
        return alvo.tipo() == AlvoDoCofre.Tipo.CREDENCIAL
                ? new RecursoNaoEncontradoException("USUARIO_NAO_ENCONTRADO", "Este usuário não existe mais.")
                : new RecursoNaoEncontradoException("ACESSO_NAO_ENCONTRADO", "Este acesso não existe mais.");
    }
}
