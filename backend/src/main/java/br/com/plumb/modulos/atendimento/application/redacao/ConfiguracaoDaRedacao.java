package br.com.plumb.modulos.atendimento.application.redacao;

import br.com.plumb.modulos.atendimento.application.analise.ConfiguracaoDaAnalise;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * O que a redação usa da configuração ({@code atendimento.gemini} e {@code atendimento.linha-do-tempo} no
 * atendimento.yml).
 *
 * @param margemDaLinha    quanto antes do início e depois do fim do atendimento a data de um arquivo enviado pela
 *                         pessoa ainda conta (fora disso, o arquivo vai para "material sem horário")
 * @param imagemMaximaNaCopia na cópia "com imagens", imagem maior que isto não é embutida (vai só a legenda)
 */
@Component
public record ConfiguracaoDaRedacao(String modelo, int tentativas, int maxTokensSaida, Double temperatura,
                                    String raciocinio, Duration margemDaLinha, long imagemMaximaNaCopia,
                                    int contextoLocal) {

    /** Quantos tokens a IA local lê de uma vez, quando a configuração não diz (atendimento.ia.local.contexto). */
    public static final int CONTEXTO_PADRAO = 24576;

    public ConfiguracaoDaRedacao(String modelo, int tentativas, int maxTokensSaida, Double temperatura, String raciocinio,
                                 Duration margemDaLinha, long imagemMaximaNaCopia) {
        this(modelo, tentativas, maxTokensSaida, temperatura, raciocinio, margemDaLinha, imagemMaximaNaCopia, CONTEXTO_PADRAO);
    }

    @Autowired
    public ConfiguracaoDaRedacao(@Value("${atendimento.ia.motor:local}") String motor,
                                 @Value("${atendimento.ia.local.modelo:gemma4:12b}") String modeloLocal,
                                 @Value("${atendimento.gemini.modelos.redacao:gemini-3.8-flash}") String modelo,
                                 @Value("${atendimento.gemini.tentativas:5}") int tentativas,
                                 @Value("${atendimento.gemini.max-tokens-saida:32768}") int maxTokensSaida,
                                 @Value("${atendimento.gemini.temperatura:}") String temperatura,
                                 @Value("${atendimento.gemini.raciocinio:}") String raciocinio,
                                 @Value("${atendimento.linha-do-tempo.margem:1h}") Duration margemDaLinha,
                                 @Value("${atendimento.copia.imagem-maxima:10MB}") DataSize imagemMaximaNaCopia,
                                 @Value("${atendimento.ia.local.contexto:24576}") int contextoLocal) {
        this(ConfiguracaoDaAnalise.local(motor) ? modeloLocal.strip() : modelo.strip(), Math.max(1, tentativas),
                maxTokensSaida,
                ConfiguracaoDaAnalise.local(motor) || temperatura == null || temperatura.isBlank() ? null
                        : Double.valueOf(temperatura.strip()),
                ConfiguracaoDaAnalise.local(motor) || raciocinio == null || raciocinio.isBlank() ? null
                        : raciocinio.strip(), margemDaLinha, imagemMaximaNaCopia.toBytes(), contextoLocal);
    }
}
