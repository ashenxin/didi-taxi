package com.sx.passengerapi.model.lifecycle;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** 新生命周期注销提交请求。 */
public record AccountCancellationSubmitRequest(
        @NotNull @PositiveOrZero Long expectedLifecycleVersion,
        @NotBlank String code,
        @NotNull @AssertTrue Boolean confirm) {
}
