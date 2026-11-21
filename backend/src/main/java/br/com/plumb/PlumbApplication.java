package br.com.plumb;

import br.com.plumb.infrastructure.config.BancoHorasProperties;
import br.com.plumb.infrastructure.armazenamento.ArmazenamentoProperties;
import br.com.plumb.infrastructure.config.PontoProperties;
import br.com.plumb.infrastructure.google.GoogleProperties;
import br.com.plumb.infrastructure.importacao.ImportacaoPdfProperties;
import br.com.plumb.infrastructure.log.LogsProperties;
import br.com.plumb.infrastructure.security.SegurancaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        PontoProperties.class,
        BancoHorasProperties.class,
        ImportacaoPdfProperties.class,
        ArmazenamentoProperties.class,
        SegurancaProperties.class,
        GoogleProperties.class,
        LogsProperties.class
})
public class PlumbApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlumbApplication.class, args);
    }
}
