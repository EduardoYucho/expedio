package br.com.expedio.modulos.clientes.infrastructure.cripto;

import br.com.expedio.domain.exception.ConflitoException;
import br.com.expedio.domain.exception.RegraNegocioException;
import br.com.expedio.modulos.clientes.application.cofre.ChavesDoCofre;
import br.com.expedio.modulos.clientes.application.cofre.RegistroDoCofre;
import br.com.expedio.modulos.clientes.infrastructure.config.ConfiguracaoDosClientes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * A chave do cofre dos clientes: 256 bits aleatórios em {@code <pasta>/chave-do-cofre}, fora do banco, lido só pelo
 * usuário do serviço.
 * <ul>
 *   <li><b>Nunca nasce por cima de dados</b>: sem o arquivo, a chave só é criada se não houver nada cifrado nem a cópia
 *       embrulhada; senão o cofre fica trancado até a frase de recuperação recriá-la.</li>
 *   <li><b>Frase de recuperação</b>: a cópia {@code chave-do-cofre.recuperacao} tem a chave cifrada com uma chave
 *       derivada da frase (PBKDF2-HMAC-SHA256, {@value #ITERACOES} iterações, sal aleatório). É ela, e não a chave
 *       crua, que o "expedio backup" copia: o backup sozinho não abre as senhas.</li>
 *   <li>Cada valor usa um vetor aleatório de 12 bytes e o id do dono como dado associado.</li>
 * </ul>
 */
@Component
public class ChaveDoCofreEmArquivo implements ChavesDoCofre {

    private static final Logger log = LoggerFactory.getLogger(ChaveDoCofreEmArquivo.class);
    static final String ARQUIVO_DA_CHAVE = "chave-do-cofre";
    static final String ARQUIVO_DA_RECUPERACAO = "chave-do-cofre.recuperacao";
    static final int ITERACOES = 600_000;
    private static final int BYTES_DA_CHAVE = 32;
    private static final int BYTES_DO_VETOR = 12;
    private static final int BITS_DA_ETIQUETA = 128;
    private static final byte[] CONTEXTO = "expedio-clientes:".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTEXTO_DA_RECUPERACAO = "expedio-clientes-recuperacao:v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom ALEATORIO = new SecureRandom();

    private final Path pasta;
    private final Path arquivo;
    private final Path recuperacao;
    private final RegistroDoCofre registro;
    private final ObjectMapper json = new ObjectMapper();
    private volatile SecretKey chave;

    public ChaveDoCofreEmArquivo(ConfiguracaoDosClientes configuracao, RegistroDoCofre registro) {
        this.pasta = configuracao.pasta().toAbsolutePath().normalize();
        this.arquivo = pasta.resolve(ARQUIVO_DA_CHAVE);
        this.recuperacao = pasta.resolve(ARQUIVO_DA_RECUPERACAO);
        this.registro = registro;
    }

    @Override
    public Cifrado cifrar(String texto, UUID dono) {
        byte[] vetor = new byte[BYTES_DO_VETOR];
        ALEATORIO.nextBytes(vetor);
        try {
            Cipher cifra = Cipher.getInstance("AES/GCM/NoPadding");
            cifra.init(Cipher.ENCRYPT_MODE, chave(true), new GCMParameterSpec(BITS_DA_ETIQUETA, vetor));
            cifra.updateAAD(associado(dono));
            return new Cifrado(cifra.doFinal(texto.getBytes(StandardCharsets.UTF_8)), vetor);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Não foi possível cifrar no cofre dos clientes", e);
        }
    }

    @Override
    public String decifrar(Cifrado cifrado, UUID dono) {
        try {
            Cipher cifra = Cipher.getInstance("AES/GCM/NoPadding");
            cifra.init(Cipher.DECRYPT_MODE, chave(false), new GCMParameterSpec(BITS_DA_ETIQUETA, cifrado.vetor()));
            cifra.updateAAD(associado(dono));
            return new String(cifra.doFinal(cifrado.dados()), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            // chave diferente da que cifrou, dono trocado ou valor alterado no banco
            log.error("Valor do cofre dos clientes não abre com a chave atual (dono {})", dono);
            throw new RegraNegocioException("COFRE_NAO_ABRE", "Esta senha não abre com a chave do cofre deste "
                    + "computador (chave trocada ou dado alterado). Avise quem administra o cofre.");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Não foi possível decifrar no cofre dos clientes", e);
        }
    }

    @Override
    public Situacao situacao() {
        if (chave != null || Files.isRegularFile(arquivo)) {
            return Situacao.PRONTA;
        }
        return Files.exists(recuperacao) || registro.haDadosCifrados() ? Situacao.TRANCADA : Situacao.NOVA;
    }

    @Override
    public boolean temRecuperacao() {
        return Files.isRegularFile(recuperacao);
    }

    @Override
    public synchronized void definirFraseDeRecuperacao(String frase) {
        byte[] crua = chave(false).getEncoded();
        byte[] sal = new byte[16];
        ALEATORIO.nextBytes(sal);
        byte[] vetor = new byte[BYTES_DO_VETOR];
        ALEATORIO.nextBytes(vetor);
        try {
            Cipher cifra = Cipher.getInstance("AES/GCM/NoPadding");
            cifra.init(Cipher.ENCRYPT_MODE, derivar(frase, sal, ITERACOES), new GCMParameterSpec(BITS_DA_ETIQUETA, vetor));
            cifra.updateAAD(CONTEXTO_DA_RECUPERACAO);
            byte[] embrulhada = cifra.doFinal(crua);
            ObjectNode conteudo = json.createObjectNode()
                    .put("versao", 1)
                    .put("algoritmo", "PBKDF2WithHmacSHA256+AES-256-GCM")
                    .put("iteracoes", ITERACOES)
                    .put("sal", Base64.getEncoder().encodeToString(sal))
                    .put("vetor", Base64.getEncoder().encodeToString(vetor))
                    .put("chave", Base64.getEncoder().encodeToString(embrulhada));
            gravarProtegido(recuperacao, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(conteudo));
            log.warn("Frase de recuperação do cofre dos clientes definida ({})", recuperacao);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Não foi possível gravar a cópia embrulhada da chave do cofre", e);
        } finally {
            Arrays.fill(crua, (byte) 0);
        }
    }

    @Override
    public synchronized void restaurarComFrase(String frase) {
        if (Files.isRegularFile(arquivo)) {
            throw new ConflitoException("COFRE_NAO_TRANCADO", "A chave do cofre já está neste computador.");
        }
        if (!Files.isRegularFile(recuperacao)) {
            throw new RegraNegocioException("SEM_COPIA_DA_CHAVE", "Não há a cópia embrulhada da chave em " + recuperacao
                    + ". Restaure o backup (expedio restaurar) e tente de novo.");
        }
        byte[] crua = null;
        try {
            JsonNode conteudo = json.readTree(recuperacao.toFile());
            byte[] sal = Base64.getDecoder().decode(conteudo.path("sal").asText());
            byte[] vetor = Base64.getDecoder().decode(conteudo.path("vetor").asText());
            byte[] embrulhada = Base64.getDecoder().decode(conteudo.path("chave").asText());
            int iteracoes = conteudo.path("iteracoes").asInt(ITERACOES);
            Cipher cifra = Cipher.getInstance("AES/GCM/NoPadding");
            cifra.init(Cipher.DECRYPT_MODE, derivar(frase, sal, iteracoes), new GCMParameterSpec(BITS_DA_ETIQUETA, vetor));
            cifra.updateAAD(CONTEXTO_DA_RECUPERACAO);
            crua = cifra.doFinal(embrulhada);
            if (crua.length != BYTES_DA_CHAVE) {
                throw new IllegalStateException("tamanho de chave inesperado");
            }
            gravarProtegido(arquivo, crua);
            chave = new SecretKeySpec(crua, "AES");
            log.warn("Chave do cofre dos clientes restaurada pela frase de recuperação ({})", arquivo);
        } catch (AEADBadTagException e) {
            throw new RegraNegocioException("FRASE_ERRADA", "A frase de recuperação não confere.");
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            throw new RegraNegocioException("COPIA_DA_CHAVE_ILEGIVEL", "A cópia embrulhada da chave está estragada ("
                    + recuperacao + "). Restaure outro backup.");
        } finally {
            if (crua != null) {
                Arrays.fill(crua, (byte) 0);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ a chave

    private SecretKey chave(boolean criarSeNova) {
        SecretKey atual = chave;
        if (atual != null) {
            return atual;
        }
        synchronized (this) {
            if (chave != null) {
                return chave;
            }
            if (Files.isRegularFile(arquivo)) {
                chave = ler();
                return chave;
            }
            if (!criarSeNova || situacao() == Situacao.TRANCADA) {
                throw new RegraNegocioException("COFRE_TRANCADO", "O cofre está trancado: a chave dele não está neste "
                        + "computador. Quem administra o cofre restaura com a frase de recuperação.");
            }
            byte[] nova = new byte[BYTES_DA_CHAVE];
            ALEATORIO.nextBytes(nova);
            try {
                gravarProtegido(arquivo, nova);
            } catch (IOException e) {
                throw new IllegalStateException("Não foi possível criar a chave do cofre em " + arquivo, e);
            }
            log.warn("Chave do cofre dos clientes criada em {} (defina a frase de recuperação na tela Clientes)", arquivo);
            chave = new SecretKeySpec(nova, "AES");
            Arrays.fill(nova, (byte) 0);
            return chave;
        }
    }

    private SecretKey ler() {
        try {
            byte[] bytes = Files.readAllBytes(arquivo);
            if (bytes.length != BYTES_DA_CHAVE) {
                throw new RegraNegocioException("CHAVE_DO_COFRE_ILEGIVEL", "O arquivo da chave do cofre (" + arquivo
                        + ") está estragado. Apague-o e restaure com a frase de recuperação.");
            }
            SecretKey lida = new SecretKeySpec(bytes, "AES");
            Arrays.fill(bytes, (byte) 0);
            return lida;
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível ler a chave do cofre em " + arquivo, e);
        }
    }

    private static SecretKey derivar(String frase, byte[] sal, int iteracoes) throws GeneralSecurityException {
        char[] caracteres = frase.strip().toCharArray();
        PBEKeySpec especificacao = new PBEKeySpec(caracteres, sal, iteracoes, 256);
        try {
            byte[] bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(especificacao).getEncoded();
            return new SecretKeySpec(bytes, "AES");
        } finally {
            especificacao.clearPassword();
            Arrays.fill(caracteres, '\0');
        }
    }

    private static byte[] associado(UUID dono) {
        return ByteBuffer.allocate(CONTEXTO.length + 16).put(CONTEXTO)
                .putLong(dono.getMostSignificantBits()).putLong(dono.getLeastSignificantBits()).array();
    }

    /** Grava num temporário da mesma pasta, restringe ao dono e só então troca (nunca deixa meio arquivo). */
    private void gravarProtegido(Path destino, byte[] conteudo) throws IOException {
        Files.createDirectories(pasta);
        Path temporario = Files.createTempFile(pasta, "cofre", ".tmp");
        try {
            restringirAoDono(temporario);
            Files.write(temporario, conteudo);
            try {
                Files.move(temporario, destino, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporario, destino, StandardCopyOption.REPLACE_EXISTING);
            }
            restringirAoDono(destino);
        } finally {
            Files.deleteIfExists(temporario);
        }
    }

    /** Só o dono do arquivo (o usuário do serviço) lê e escreve. Se o sistema de arquivos não deixar, só avisa. */
    private static void restringirAoDono(Path caminho) {
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(caminho, PosixFileAttributeView.class);
            if (posix != null) {
                posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
                return;
            }
            AclFileAttributeView acl = Files.getFileAttributeView(caminho, AclFileAttributeView.class);
            if (acl != null) {
                AclEntry soODono = AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(acl.getOwner())
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .build();
                acl.setAcl(List.of(soODono));
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            log.warn("Não foi possível restringir o acesso a {}: {}", caminho.getFileName(), e.toString());
        }
    }
}
