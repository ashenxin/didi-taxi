package com.sx.driverapi.model.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SmsSendRequest {
    @NotBlank(message = "手机号不能为空")
    private String phone;
}

