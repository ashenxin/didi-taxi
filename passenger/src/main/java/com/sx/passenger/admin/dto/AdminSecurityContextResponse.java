package com.sx.passenger.admin.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class AdminSecurityContextResponse {

    private Long userId;
    private String username;
    private String displayName;
    private String provinceCode;
    private String cityCode;
    private Long tokenVersion;
    private Integer status;
    private List<String> roleCodes = new ArrayList<>();
    private List<Long> menuIds = new ArrayList<>();
}
