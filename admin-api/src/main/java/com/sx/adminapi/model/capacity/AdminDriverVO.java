package com.sx.adminapi.model.capacity;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class AdminDriverVO {
    private Long id;
    private Integer driverSource;
    private String cityCode;
    private String cityName;
    private Long companyId;
    private String brandNo;
    private String brandName;
    private String name;
    private String idCard;
    private String phone;
    private Integer gender;
    private Integer rptStatus;
    private Integer monitorStatus;
    private Date createdAt;
    private Date updatedAt;
}

