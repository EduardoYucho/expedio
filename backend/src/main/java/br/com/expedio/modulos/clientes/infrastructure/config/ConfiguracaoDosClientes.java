package br.com.expedio.modulos.clientes.infrastructure.config;

import br.com.expedio.modulos.clientes.application.cofre.CofreDeAcessos;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Configuração dos Clientes (clientes.yml; os valores desta máquina vão em config/application.yml).
 *
 * @param pasta onde ficam a chave do cofre e a cópia dela embrulhada pela frase de recuperação
 * @param cofre quanto tempo o cofre fica aberto e o limite de códigos errados
 */
@ConfigurationProperties(prefix = "clientes")
public record ConfiguracaoDosClientes(Path pasta, Cofre cofre) {

    public record Cofre(Integer minutosAberto, Integer tentativas, Integer minutosDeBloqueio) {
    }

    public ConfiguracaoDosClientes {
        if (pasta == null) {
            pasta = Path.of(System.getProperty("user.home"), ".expedio", "clientes");
        }
        if (cofre == null) {
            cofre = new Cofre(null, null, null);
        }
    }

    public CofreDeAcessos.Parametros parametros() {
        return new CofreDeAcessos.Parametros(
                Duration.ofMinutes(entre(cofre.minutosAberto(), 10, 1, 60)),
                entre(cofre.tentativas(), 5, 3, 20),
                Duration.ofMinutes(entre(cofre.minutosDeBloqueio(), 15, 1, 24 * 60)));
    }

    private static int entre(Integer valor, int padrao, int minimo, int maximo) {
        return valor == null ? padrao : Math.max(minimo, Math.min(maximo, valor));
    }

    /** Registra a configuração e os parâmetros do cofre (o módulo não depende do ponto para isso). */
    @Configuration
    @EnableConfigurationProperties(ConfiguracaoDosClientes.class)
    public static class Registro {

        @Bean
        public CofreDeAcessos.Parametros parametrosDoCofre(ConfiguracaoDosClientes configuracao) {
            return configuracao.parametros();
        }
    }
}
