package br.com.expedio.modulos.clientes.infrastructure.security;

import br.com.expedio.infrastructure.log.FiltroUsuarioNoLog;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Segurança das rotas dos Clientes, separada da do Expedio: o mesmo login (JWT) e as mesmas permissões (relidas a
 * cada requisição). Quem decide é a permissão clientes.ver ({@code InterceptorDosClientes}); as senhas exigem ainda
 * clientes.senhas e o cofre aberto ({@code CofreDeAcessos}). As respostas não ficam em cache (padrão do Spring
 * Security) nem mandam o endereço para outros sites.
 *
 * <pre>
 * Rota                                         Exige
 * GET  /api/v1/clientes/meu-acesso             sessão
 * /api/v1/clientes/** (o resto)                clientes.ver (no InterceptorDosClientes) + a regra de cada rota
 * </pre>
 */
@Configuration
public class SegurancaDosClientes {

    @Bean
    @Order(6)
    SecurityFilterChain cadeiaDosClientes(HttpSecurity http, AuthenticationEntryPoint semSessao,
                                          AccessDeniedHandler semPermissao, JwtAuthenticationConverter conversor)
            throws Exception {
        http
                .securityMatcher("/api/v1/clientes", "/api/v1/clientes/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .headers(h -> h.referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(regras -> regras
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(conversor))
                        .authenticationEntryPoint(semSessao)
                        .accessDeniedHandler(semPermissao))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(semSessao)
                        .accessDeniedHandler(semPermissao))
                .addFilterAfter(new FiltroUsuarioNoLog(), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
