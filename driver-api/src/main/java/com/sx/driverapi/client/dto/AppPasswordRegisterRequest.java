package com.sx.driverapi.client.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AppPasswordRegisterRequest {
    private String phone;
    private String code;
    private String password;
}

