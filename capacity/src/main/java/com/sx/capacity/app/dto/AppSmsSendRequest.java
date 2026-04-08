package com.sx.capacity.app.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AppSmsSendRequest {
    @NotBlank(message = "手机号不能为空")
    private String phone;
}

