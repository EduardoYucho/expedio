package br.com.expedio.modulos.clientes.infrastructure.web;

import br.com.expedio.domain.model.Usuario;
import br.com.expedio.infrastructure.web.acesso.AcessoUsuarios;
import br.com.expedio.infrastructure.web.dto.ApiResponse;
import br.com.expedio.modulos.clientes.application.acesso.PermissoesDosClientes;
import br.com.expedio.modulos.clientes.application.ficha.Ficha;
import br.com.expedio.modulos.clientes.application.ficha.FichasDeClientes;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * A ficha dos clientes. Nenhuma resposta daqui leva senha (elas saem só pelo {@link CofreController}).
 *
 * <pre>
 * GET    /api/v1/clientes/meu-acesso                 {ver, editar, senhas, administrar}
 * GET    /api/v1/clientes?busca=&situacao=ativos     a lista (nome, cidade, grupo, contato, ID de acesso)
 * GET    /api/v1/clientes/grupos                     os grupos já usados
 * POST   /api/v1/clientes                            cliente novo (clientes.editar)
 * GET    /api/v1/clientes/{id}                       a ficha inteira
 * PUT    /api/v1/clientes/{id}                       altera (clientes.editar)
 * DELETE /api/v1/clientes/{id}                       apaga (clientes.administrar)
 * POST/PUT/DELETE /api/v1/clientes/{id}/{alertas|contatos|maquinas|acessos|anotacoes}[/{item}]  (clientes.editar)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/clientes")
public class ClientesController {

    public record MeuAcesso(boolean ver, boolean editar, boolean senhas, boolean administrar) {
    }

    public record Criado(UUID id) {
    }

    private final AcessoUsuarios acesso;
    private final FichasDeClientes fichas;

    public ClientesController(AcessoUsuarios acesso, FichasDeClientes fichas) {
        this.acesso = acesso;
        this.fichas = fichas;
    }

    @GetMapping("/meu-acesso")
    public ApiResponse<MeuAcesso> meuAcesso() {
        Usuario u = acesso.logado();
        boolean ativo = u.ativo();
        return ApiResponse.ok(new MeuAcesso(ativo && u.pode(PermissoesDosClientes.VER),
                ativo && u.pode(PermissoesDosClientes.EDITAR), ativo && u.pode(PermissoesDosClientes.SENHAS),
                ativo && u.pode(PermissoesDosClientes.ADMINISTRAR)));
    }

    @GetMapping
    public ApiResponse<List<Ficha.Resumo>> buscar(@RequestParam(required = false) String busca,
                                                 @RequestParam(defaultValue = "ativos") String situacao) {
        return ApiResponse.ok(fichas.buscar(busca, situacao));
    }

    @GetMapping("/grupos")
    public ApiResponse<List<String>> grupos() {
        return ApiResponse.ok(fichas.grupos());
    }

    @PostMapping
    public ApiResponse<Criado> criar(@RequestBody Ficha.PedidoCliente pedido) {
        return ApiResponse.ok(new Criado(fichas.criar(acesso.logado(), pedido)));
    }

    @GetMapping("/{id}")
    public ApiResponse<Ficha.Completa> ficha(@PathVariable UUID id) {
        return ApiResponse.ok(fichas.ficha(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> alterar(@PathVariable UUID id, @RequestBody Ficha.PedidoCliente pedido) {
        fichas.alterar(acesso.logado(), id, pedido);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> apagar(@PathVariable UUID id) {
        fichas.apagar(acesso.logado(), id);
        return ApiResponse.ok(null);
    }

    // ------------------------------------------------------------------------------------------ alertas

    @PostMapping("/{id}/alertas")
    public ApiResponse<Criado> criarAlerta(@PathVariable UUID id, @RequestBody Ficha.PedidoAlerta pedido) {
        return ApiResponse.ok(new Criado(fichas.criarAlerta(acesso.logado(), id, pedido)));
    }

    @PutMapping("/{id}/alertas/{item}")
    public ApiResponse<Void> alterarAlerta(@PathVariable UUID id, @PathVariable UUID item,
                                           @RequestBody Ficha.PedidoAlerta pedido) {
        fichas.alterarAlerta(acesso.logado(), id, item, pedido);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{id}/alertas/{item}")
    public ApiResponse<Void> apagarAlerta(@PathVariable UUID id, @PathVariable UUID item) {
        fichas.apagarAlerta(acesso.logado(), id, item);
        return ApiResponse.ok(null);
    }

    // ------------------------------------------------------------------------------------------ contatos

    @PostMapping("/{id}/contatos")
    public ApiResponse<Criado> criarContato(@PathVariable UUID id, @RequestBody Ficha.PedidoContato pedido) {
        return ApiResponse.ok(new Criado(fichas.criarContato(acesso.logado(), id, pedido)));
    }

    @PutMapping("/{id}/contatos/{item}")
    public ApiResponse<Void> alterarContato(@PathVariable UUID id, @PathVariable UUID item,
                                            @RequestBody Ficha.PedidoContato pedido) {
        fichas.alterarContato(acesso.logado(), id, item, pedido);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{id}/contatos/{item}")
    public ApiResponse<Void> apagarContato(@PathVariable UUID id, @PathVariable UUID item) {
        fichas.apagarContato(acesso.logado(), id, item);
        return ApiResponse.ok(null);
    }

    // ------------------------------------------------------------------------------------------ máquinas

    @PostMapping("/{id}/maquinas")
    public ApiResponse<Criado> criarMaquina(@PathVariable UUID id, @RequestBody Ficha.PedidoMaquina pedido) {
        return ApiResponse.ok(new Criado(fichas.criarMaquina(acesso.logado(), id, pedido)));
    }

    @PutMapping("/{id}/maquinas/{item}")
    public ApiResponse<Void> alterarMaquina(@PathVariable UUID id, @PathVariable UUID item,
                                            @RequestBody Ficha.PedidoMaquina pedido) {
        fichas.alterarMaquina(acesso.logado(), id, item, pedido);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{id}/maquinas/{item}")
    public ApiResponse<Void> apagarMaquina(@PathVariable UUID id, @PathVariable UUID item) {
        fichas.apagarMaquina(acesso.logado(), id, item);
        return ApiResponse.ok(null);
    }

    // ------------------------------------------------------------------------------------------ acessos (sem senha)

    @PostMapping("/{id}/acessos")
    public ApiResponse<Criado> criarAcesso(@PathVariable UUID id, @RequestBody Ficha.PedidoAcesso pedido) {
        return ApiResponse.ok(new Criado(fichas.criarAcesso(acesso.logado(), id, pedido)));
    }

    @PutMapping("/{id}/acessos/{item}")
    public ApiResponse<Void> alterarAcesso(@PathVariable UUID id, @PathVariable UUID item,
                                           @RequestBody Ficha.PedidoAcesso pedido) {
        fichas.alterarAcesso(acesso.logado(), id, item, pedido);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{id}/acessos/{item}")
    public ApiResponse<Void> apagarAcesso(@PathVariable UUID id, @PathVariable UUID item) {
        fichas.apagarAcesso(acesso.logado(), id, item);
        return ApiResponse.ok(null);
    }

    // ------------------------------------------------------------------------------------------ anotações

    @PostMapping("/{id}/anotacoes")
    public ApiResponse<Criado> criarAnotacao(@PathVariable UUID id, @RequestBody Ficha.PedidoAnotacao pedido) {
        return ApiResponse.ok(new Criado(fichas.criarAnotacao(acesso.logado(), id, pedido)));
    }

    @DeleteMapping("/{id}/anotacoes/{item}")
    public ApiResponse<Void> apagarAnotacao(@PathVariable UUID id, @PathVariable UUID item) {
        fichas.apagarAnotacao(acesso.logado(), id, item);
        return ApiResponse.ok(null);
    }
}
