package com.sx.adminapi.model.capacity;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class AdminCompanyVO {
    private Long id;
    @JsonAlias({ "city_code" })
    private String cityCode;
    @JsonAlias({ "city_name" })
    private String cityName;
    @JsonAlias({ "province_code" })
    private String provinceCode;
    /** 省份名称（与 provinceCode 对应，列表展示用） */
    @JsonAlias({ "province_name" })
    private String provinceName;
    private String companyNo;
    private String companyName;
    private Long teamId;
    private String team;
    private Date createdAt;
    private Date updatedAt;
}

