package br.com.plumb.modulos.clientes.infrastructure.web;

import br.com.plumb.domain.exception.AcessoNegadoException;
import br.com.plumb.domain.model.Usuario;
import br.com.plumb.infrastructure.web.acesso.AcessoUsuarios;
import br.com.plumb.modulos.clientes.application.acesso.PermissoesDosClientes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Toda rota dos Clientes (menos o "meu acesso") exige clientes.ver: as rotas novas já nascem protegidas. O
 * interceptor nasce aqui, num @Bean, e não como componente: assim não entra nos testes de controlador do Plumb.
 */
@Configuration
public class WebDosClientes {

    static final String ROTAS = "/api/v1/clientes/**";

    @Bean
    WebMvcConfigurer rotasDosClientes(AcessoUsuarios acesso) {
        Interceptor interceptor = new Interceptor(acesso);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registro) {
                registro.addInterceptor(interceptor)
                        .addPathPatterns("/api/v1/clientes", ROTAS)
                        .excludePathPatterns("/api/v1/clientes/meu-acesso");
            }
        };
    }

    static final class Interceptor implements HandlerInterceptor {

        private final AcessoUsuarios acesso;

        Interceptor(AcessoUsuarios acesso) {
            this.acesso = acesso;
        }

        @Override
        public boolean preHandle(HttpServletRequest requisicao, HttpServletResponse resposta, Object destino) {
            Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
            if (CorsUtils.isPreFlightRequest(requisicao) || autenticacao == null || !autenticacao.isAuthenticated()
                    || autenticacao instanceof AnonymousAuthenticationToken) {
                return true; // a sessão é exigida antes, pela cadeia de segurança do módulo
            }
            Usuario logado = acesso.logado();
            if (logado.trocarSenha()) {
                throw new AcessoNegadoException("TROCAR_SENHA", "Troque a senha provisória para continuar.");
            }
            if (!logado.pode(PermissoesDosClientes.VER)) {
                throw new AcessoNegadoException("MODULO_NAO_LIBERADO",
                        "Os clientes não estão liberados para você. Peça a quem cuida dos acessos.");
            }
            return true;
        }
    }
}
