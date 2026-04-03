package com.sx.adminapi.model.capacity;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class AdminCompanyVO {
    private Long id;
    private String cityCode;
    private String cityName;
    private String provinceCode;
    private String companyNo;
    private String companyName;
    private Long teamId;
    private String team;
    private Date createdAt;
    private Date updatedAt;
}

