package com.sx.adminapi.client.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class PassengerVerifyCredentialsData {

    private Long userId;

    private Long tokenVersion;

    private Integer status;
}
