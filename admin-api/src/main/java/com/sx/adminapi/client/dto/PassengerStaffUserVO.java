package com.sx.adminapi.client.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class PassengerStaffUserVO {

    private Long id;
    private String username;
    private String displayName;
    private String provinceCode;
    private String cityCode;
    private Integer status;
    private String roleCode;
}
