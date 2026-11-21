package br.com.plumb.infrastructure.config;

import br.com.plumb.domain.acesso.CatalogoDePermissoes;
import br.com.plumb.domain.acesso.FonteDePermissoes;
import br.com.plumb.domain.acesso.PermissoesDaAdministracao;
import br.com.plumb.domain.acesso.PermissoesDoPonto;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * O catálogo de permissões: as do ponto e da administração (daqui) e as de cada módulo (os beans
 * {@link FonteDePermissoes} que cada um declara). Uma permissão repetida ou com requisito que não existe impede o
 * sistema de subir, com a mensagem de qual é.
 */
@Configuration
public class PermissoesConfig {

    @Bean
    PermissoesDoPonto permissoesDoPonto() {
        return new PermissoesDoPonto();
    }

    @Bean
    PermissoesDaAdministracao permissoesDaAdministracao() {
        return new PermissoesDaAdministracao();
    }

    @Bean
    CatalogoDePermissoes catalogoDePermissoes(List<FonteDePermissoes> fontes) {
        return new CatalogoDePermissoes(fontes);
    }
}
