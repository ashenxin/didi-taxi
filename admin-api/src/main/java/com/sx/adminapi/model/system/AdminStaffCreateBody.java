package com.sx.adminapi.model.system;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminStaffCreateBody {

    @NotBlank
    private String username;

    @NotBlank
    private String password;

    private String displayName;

    /** PROVINCE_ADMIN 或 CITY_OPERATOR */
    @NotBlank
    private String roleCode;

    @NotBlank
    private String provinceCode;

    private String cityCode;
}
