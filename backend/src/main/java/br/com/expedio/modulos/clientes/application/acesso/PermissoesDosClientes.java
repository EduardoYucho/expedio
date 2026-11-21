package br.com.expedio.modulos.clientes.application.acesso;

import br.com.expedio.domain.acesso.FonteDePermissoes;
import br.com.expedio.domain.acesso.Permissao;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * As permissões dos Clientes, na tela de perfis (grupo "Suporte"). Toda rota exige {@link #VER}; as senhas exigem
 * {@link #SENHAS} e, além dela, o cofre aberto pelo código do app autenticador.
 */
@Component
public class PermissoesDosClientes implements FonteDePermissoes {

    /** A ficha: dados, alertas, contatos, máquinas e os IDs dos acessos (sem as senhas). */
    public static final String VER = "clientes.ver";
    /** Cadastra e altera clientes e o que há na ficha (inclui ver). */
    public static final String EDITAR = "clientes.editar";
    /** Mostra, copia e altera as senhas dos acessos, com o código do celular (inclui ver). */
    public static final String SENHAS = "clientes.senhas";
    /** Vê quem usou cada senha, zera o autenticador de alguém, cuida da frase de recuperação e apaga clientes. */
    public static final String ADMINISTRAR = "clientes.administrar";

    @Override
    public String grupo() {
        return "Suporte";
    }

    @Override
    public int ordem() {
        return 42;
    }

    @Override
    public List<Permissao> permissoes() {
        return List.of(
                Permissao.de(VER, "Ver os clientes",
                        "Vê a ficha dos clientes: alertas, contatos, máquinas e os IDs dos acessos (sem as senhas)."),
                Permissao.de(EDITAR, "Cadastrar e alterar clientes",
                        "Cadastra clientes e altera a ficha (alertas, contatos, máquinas, acessos, anotações).").requer(VER),
                Permissao.de(SENHAS, "Usar as senhas dos clientes",
                        "Mostra, copia e altera as senhas dos acessos, confirmando com o código do celular.").requer(VER),
                Permissao.de(ADMINISTRAR, "Administrar o cofre dos clientes",
                        "Vê quem usou cada senha, zera o autenticador de alguém, cuida da frase de recuperação e "
                                + "apaga clientes.").requer(VER));
    }
}
