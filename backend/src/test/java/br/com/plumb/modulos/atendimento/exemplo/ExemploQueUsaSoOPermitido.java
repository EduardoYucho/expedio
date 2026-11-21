package br.com.plumb.modulos.atendimento.exemplo;

import br.com.plumb.domain.exception.RegraNegocioException;
import br.com.plumb.domain.model.Usuario;
import br.com.plumb.infrastructure.web.dto.ApiResponse;

/** Só para o RegraDosModulosPegaViolacaoTest: um "módulo" usando apenas o que o ponto oferece aos módulos. */
public class ExemploQueUsaSoOPermitido {

    public ApiResponse<String> usar(Usuario usuario) {
        if (usuario == null) {
            throw new RegraNegocioException("SEM_USUARIO", "Sem usuário.");
        }
        return ApiResponse.ok(usuario.login() + " " + usuario.pode("atendimentos.usar"));
    }
}
