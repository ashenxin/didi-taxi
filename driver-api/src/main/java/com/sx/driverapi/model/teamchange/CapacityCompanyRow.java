package com.sx.driverapi.model.teamchange;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CapacityCompanyRow {
    private Long id;
    private String cityCode;
    private String cityName;
    private String provinceCode;
    private String provinceName;
    private String companyNo;
    private String companyName;
    private Long teamId;
    private String team;
}

