package br.com.plumb.infrastructure.web.dto;

import br.com.plumb.application.usecase.GerenciarCicloBancoUseCase;

public record FechamentoCicloResponse(CicloBancoResponse fechado, CicloBancoResponse novo) {

    public static FechamentoCicloResponse de(GerenciarCicloBancoUseCase.Fechamento f) {
        return new FechamentoCicloResponse(CicloBancoResponse.de(f.fechado()), CicloBancoResponse.de(f.novo()));
    }
}
