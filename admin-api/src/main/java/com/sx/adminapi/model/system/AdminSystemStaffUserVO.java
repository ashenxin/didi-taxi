package com.sx.adminapi.model.system;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AdminSystemStaffUserVO {

    private Long id;
    private String username;
    private String displayName;
    private String provinceCode;
    private String cityCode;
    private Integer status;
    private String roleCode;
}
