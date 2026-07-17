package com.sx.calculate.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FinalFareRequest {
    @NotBlank
    private String fareRuleSnapshot;
    @NotBlank
    private String fareCalculationVersion;
    @NotNull
    @PositiveOrZero
    private Long billingDistanceMeters;
    @NotNull
    @PositiveOrZero
    private Long billingDurationSeconds;
}
