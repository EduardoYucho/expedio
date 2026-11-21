package br.com.expedio.modulos.clientes.infrastructure.web;

import br.com.expedio.infrastructure.web.acesso.AcessoUsuarios;
import br.com.expedio.infrastructure.web.dto.ApiResponse;
import br.com.expedio.modulos.clientes.application.cofre.CofreDeAcessos;
import br.com.expedio.modulos.clientes.application.cofre.RegistroDoCofre;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * O cofre das senhas dos clientes. Tudo aqui exige clientes.ver (interceptor) e, conforme a rota, clientes.senhas
 * com o cofre aberto ({@value #COFRE}: o token que {@code /abrir} devolve) ou clientes.administrar. As senhas só
 * aparecem nas respostas de {@code POST /acessos/{id}/senha}, sem cache.
 *
 * <pre>
 * GET    /api/v1/clientes/cofre/estado                       o cofre para esta pessoa (aberto? autenticador?)
 * POST   /api/v1/clientes/cofre/autenticador                 QR code novo (clientes.senhas)
 * POST   /api/v1/clientes/cofre/autenticador/ativar {codigo} confirma o app e abre o cofre
 * POST   /api/v1/clientes/cofre/abrir {codigo}               abre o cofre (token, abertoAte)
 * POST   /api/v1/clientes/cofre/fechar                       fecha
 * POST   /api/v1/clientes/cofre/acessos/{id}/senha {acao}    a senha (MOSTRAR ou COPIAR), com registro
 * PUT    /api/v1/clientes/cofre/acessos/{id}/senha {senha}   grava (vazia: apaga)
 * GET    /api/v1/clientes/cofre/usos?cliente=&limite=        quem usou as senhas (clientes.administrar)
 * GET    /api/v1/clientes/cofre/autenticadores               quem tem autenticador (clientes.administrar)
 * DELETE /api/v1/clientes/cofre/autenticadores/{usuario}     zera o de alguém (clientes.administrar)
 * POST   /api/v1/clientes/cofre/recuperacao {frase}          define a frase de recuperação (administrar, cofre aberto)
 * POST   /api/v1/clientes/cofre/recuperacao/restaurar {frase} recria a chave num cofre trancado (administrar)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/clientes/cofre")
public class CofreController {

    static final String COFRE = "X-Cofre";

    public record PedidoCodigo(String codigo) {
    }

    public record PedidoSenha(String acao) {
    }

    public record NovaSenha(String senha) {
    }

    public record PedidoFrase(String frase) {
    }

    public record Senha(String senha) {
    }

    private final AcessoUsuarios acesso;
    private final CofreDeAcessos cofre;

    public CofreController(AcessoUsuarios acesso, CofreDeAcessos cofre) {
        this.acesso = acesso;
        this.cofre = cofre;
    }

    @GetMapping("/estado")
    public ApiResponse<CofreDeAcessos.Estado> estado(@RequestHeader(name = COFRE, required = false) String token) {
        return ApiResponse.ok(cofre.estado(acesso.logado(), token));
    }

    @PostMapping("/autenticador")
    public ApiResponse<CofreDeAcessos.NovoAutenticador> novoAutenticador() {
        return ApiResponse.ok(cofre.novoAutenticador(acesso.logado()));
    }

    @PostMapping("/autenticador/ativar")
    public ApiResponse<CofreDeAcessos.Abertura> ativar(@RequestBody PedidoCodigo pedido, HttpServletRequest requisicao) {
        return ApiResponse.ok(cofre.ativar(acesso.logado(), codigo(pedido), requisicao.getRemoteAddr()));
    }

    @PostMapping("/abrir")
    public ApiResponse<CofreDeAcessos.Abertura> abrir(@RequestBody PedidoCodigo pedido, HttpServletRequest requisicao) {
        return ApiResponse.ok(cofre.abrir(acesso.logado(), codigo(pedido), requisicao.getRemoteAddr()));
    }

    @PostMapping("/fechar")
    public ApiResponse<Void> fechar(@RequestHeader(name = COFRE, required = false) String token) {
        cofre.fechar(token);
        return ApiResponse.ok(null);
    }

    @PostMapping("/acessos/{id}/senha")
    public ResponseEntity<ApiResponse<Senha>> senha(@PathVariable UUID id, @RequestBody(required = false) PedidoSenha pedido,
                                                    @RequestHeader(name = COFRE, required = false) String token,
                                                    HttpServletRequest requisicao) {
        String senha = cofre.senha(acesso.logado(), token, id, pedido == null ? null : pedido.acao(),
                requisicao.getRemoteAddr());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(new Senha(senha)));
    }

    @PutMapping("/acessos/{id}/senha")
    public ApiResponse<Void> gravarSenha(@PathVariable UUID id, @RequestBody(required = false) NovaSenha pedido,
                                         @RequestHeader(name = COFRE, required = false) String token,
                                         HttpServletRequest requisicao) {
        cofre.gravarSenha(acesso.logado(), token, id, pedido == null ? null : pedido.senha(), requisicao.getRemoteAddr());
        return ApiResponse.ok(null);
    }

    @GetMapping("/usos")
    public ApiResponse<List<RegistroDoCofre.Uso>> usos(@RequestParam(required = false) UUID cliente,
                                                       @RequestParam(defaultValue = "200") int limite) {
        return ApiResponse.ok(cofre.usos(acesso.logado(), cliente, limite));
    }

    @GetMapping("/autenticadores")
    public ApiResponse<List<RegistroDoCofre.AutenticadorDaPessoa>> autenticadores() {
        return ApiResponse.ok(cofre.autenticadores(acesso.logado()));
    }

    @DeleteMapping("/autenticadores/{usuario}")
    public ApiResponse<Void> zerarAutenticador(@PathVariable UUID usuario, HttpServletRequest requisicao) {
        cofre.zerarAutenticador(acesso.logado(), usuario, requisicao.getRemoteAddr());
        return ApiResponse.ok(null);
    }

    @PostMapping("/recuperacao")
    public ApiResponse<Void> definirFrase(@RequestBody PedidoFrase pedido,
                                          @RequestHeader(name = COFRE, required = false) String token,
                                          HttpServletRequest requisicao) {
        cofre.definirFraseDeRecuperacao(acesso.logado(), token, pedido == null ? null : pedido.frase(),
                requisicao.getRemoteAddr());
        return ApiResponse.ok(null);
    }

    @PostMapping("/recuperacao/restaurar")
    public ApiResponse<Void> restaurar(@RequestBody PedidoFrase pedido, HttpServletRequest requisicao) {
        cofre.restaurarChave(acesso.logado(), pedido == null ? null : pedido.frase(), requisicao.getRemoteAddr());
        return ApiResponse.ok(null);
    }

    private static String codigo(PedidoCodigo pedido) {
        return pedido == null ? null : pedido.codigo();
    }
}
