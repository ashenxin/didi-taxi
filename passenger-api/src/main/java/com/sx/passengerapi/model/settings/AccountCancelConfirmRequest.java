package com.sx.passengerapi.model.settings;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class AccountCancelConfirmRequest {
    @NotBlank(message = "验证码不能为空")
    private String code;

    @NotNull(message = "请确认注销风险后再提交")
    @AssertTrue(message = "请确认注销风险后再提交")
    private Boolean confirm;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Boolean getConfirm() {
        return confirm;
    }

    public void setConfirm(Boolean confirm) {
        this.confirm = confirm;
    }
}
