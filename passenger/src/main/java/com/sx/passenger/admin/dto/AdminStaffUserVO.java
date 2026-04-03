package com.sx.passenger.admin.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AdminStaffUserVO {

    private Long id;

    private String username;

    private String displayName;

    private String provinceCode;

    private String cityCode;

    private Integer status;

    /** PROVINCE_ADMIN 或 CITY_OPERATOR */
    private String roleCode;
}
