package com.sx.adminapi.model.capacity;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class AdminCarVO {
    private Long id;
    private String brandNo;
    private String brandName;
    private String cityCode;
    private String cityName;
    private String carNo;
    private String plateColor;
    private String vehicleType;
    private String ownerName;
    private Date certifyDateA;
    private String fuelType;
    private String rideTypeId;
    private String businessTypeId;
    private Integer carState;
    private Integer carNum;
    private Date createdAt;
    private Date updatedAt;
}

