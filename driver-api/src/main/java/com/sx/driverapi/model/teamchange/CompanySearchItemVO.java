package com.sx.driverapi.model.teamchange;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CompanySearchItemVO {
    private Long companyId;
    private String companyName;
    private Long teamId;
    private String team;
    private String cityCode;
    private String cityName;
    private String provinceCode;
    private String provinceName;
}

