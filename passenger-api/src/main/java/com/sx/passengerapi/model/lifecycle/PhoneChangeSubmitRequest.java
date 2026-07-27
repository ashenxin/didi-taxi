package com.sx.passengerapi.model.lifecycle;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/** 新生命周期换号提交请求。 */
public record PhoneChangeSubmitRequest(
        @NotNull @PositiveOrZero Long expectedLifecycleVersion,
        @NotBlank @Pattern(regexp = "^1\\d{10}$") String newPhone,
        @NotBlank String code) {
}
