package com.sx.adminapi.model.auth;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserVO {

    private Long id;

    private String username;

    private String displayName;

    private List<String> roleCodes;

    private String provinceCode;

    private String cityCode;

    private Long tokenVersion;
}
