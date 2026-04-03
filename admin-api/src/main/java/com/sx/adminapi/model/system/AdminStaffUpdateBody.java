package com.sx.adminapi.model.system;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminStaffUpdateBody {

    private String displayName;
    private Integer status;
    private String password;
    private String provinceCode;
    private String cityCode;
}
