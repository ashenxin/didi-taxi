package com.sx.passenger.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminStaffCreateRequest {

    @NotBlank
    private String username;

    @NotBlank
    private String password;

    private String displayName;

    /** PROVINCE_ADMIN 或 CITY_OPERATOR */
    @NotBlank
    private String roleCode;

    /** 省管、市管均需；省管为管辖省 */
    @NotBlank
    private String provinceCode;

    /** 仅市管必填 */
    private String cityCode;
}
