package com.sx.adminapi.client.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PassengerStaffCreateRequest {

    private String username;
    private String password;
    private String displayName;
    private String roleCode;
    private String provinceCode;
    private String cityCode;
}
