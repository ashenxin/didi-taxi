package com.sx.adminapi.client.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class PassengerStaffUpdateRequest {

    private String displayName;
    private Integer status;
    private String password;
    private String provinceCode;
    private String cityCode;
}
