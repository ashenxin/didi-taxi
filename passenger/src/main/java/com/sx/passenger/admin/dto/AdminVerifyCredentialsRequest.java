package com.sx.passenger.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminVerifyCredentialsRequest {

    @NotBlank
    private String username;

    @NotBlank
    private String password;
}
