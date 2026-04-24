package com.sx.capacity.model.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 车辆列表/详情返回行：车辆 + 司机 + 公司信息（供管理端聚合展示）。
 * 与 {@code car} / {@code driver} / {@code company} 三表 join 对齐。
 */
@Getter
@Setter
public class CarPageRow {
    private Long id;
    private Long driverId;
    private String driverName;
    private String driverPhone;

    /** 司机归属公司（可能为空：换队解绑期等）。 */
    private Long companyId;
    private String companyName;
    private String team;

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
    private String photoOss;
    private String withPhotoOss;
    private String rideTypeId;
    private String businessTypeId;
    private Integer carState;
    private Integer carNum;
    private Date createdAt;
    private Date updatedAt;
}

