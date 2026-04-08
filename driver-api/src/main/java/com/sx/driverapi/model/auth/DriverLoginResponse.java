package com.sx.driverapi.model.auth;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DriverLoginResponse {
    private String accessToken;
    private String tokenType;
    private Long expiresIn;
    private DriverProfileVO driver;
}

