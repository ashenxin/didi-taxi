package com.sx.driverapi.model.teamchange;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SubmitTeamChangeBody {
    @NotNull(message = "toCompanyId不能为空")
    private Long toCompanyId;
    private String requestReason;
}

