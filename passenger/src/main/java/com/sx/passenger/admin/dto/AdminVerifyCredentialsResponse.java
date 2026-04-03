package com.sx.passenger.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AdminVerifyCredentialsResponse {

    private Long userId;
    private Long tokenVersion;
    private Integer status;
}
