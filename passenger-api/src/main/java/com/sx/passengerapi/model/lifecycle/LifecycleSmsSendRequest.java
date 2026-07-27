package com.sx.passengerapi.model.lifecycle;

import jakarta.validation.constraints.Pattern;

/** 生命周期验证码请求；注销时 phone 为空，换号时为新手机号。 */
public record LifecycleSmsSendRequest(
        @Pattern(regexp = "^1\\d{10}$") String phone) {
}
