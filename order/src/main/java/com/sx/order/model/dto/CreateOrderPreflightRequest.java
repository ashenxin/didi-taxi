package com.sx.order.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateOrderPreflightRequest(
        @NotNull(message = "passengerId不能为空") Long passengerId,
        @NotBlank(message = "provinceCode不能为空") String provinceCode,
        @NotBlank(message = "cityCode不能为空") String cityCode,
        @NotBlank(message = "productCode不能为空") String productCode,
        @Valid @NotNull(message = "origin不能为空") Place origin,
        @Valid @NotNull(message = "dest不能为空") Place dest) {
}
