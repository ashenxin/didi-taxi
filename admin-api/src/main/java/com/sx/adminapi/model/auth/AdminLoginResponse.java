package com.sx.adminapi.model.auth;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AdminLoginResponse {

    private String accessToken;

    private String tokenType;

    private long expiresIn;

    private AdminUserVO user;
}
