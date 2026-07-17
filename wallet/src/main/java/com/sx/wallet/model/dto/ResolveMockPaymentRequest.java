package com.sx.wallet.model.dto;

import jakarta.validation.constraints.NotBlank;

public class ResolveMockPaymentRequest {
    @NotBlank
    private String token;
    @NotBlank
    private String status;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
