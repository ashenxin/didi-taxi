package com.sx.passenger.admin.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminStaffUpdateRequest {

    private String displayName;

    /** 1 正常 0 停用 */
    private Integer status;

    /** 非空则重置密码 */
    private String password;

    /** 仅市管可改；超管可改省管/市管辖区 */
    private String provinceCode;

    private String cityCode;
}
