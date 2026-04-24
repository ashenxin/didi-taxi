package com.sx.driverapi.model.teamchange;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 对齐 capacity 返回的 DriverTeamChangeRequestVO 字段（team* 对应 company 维度）。
 */
@Getter
@Setter
public class CapacityDriverTeamChangeRequestVO {
    private Long id;
    private String status;
    private Date requestedAt;
    private String requestedBy;
    private Date reviewedAt;
    private String reviewedBy;
    private String reviewReason;
    private String requestReason;

    private Long driverId;
    private String driverName;
    private String driverPhone;
    private String driverCityCode;

    private Long fromTeamId;
    private String fromTeamName;
    private Long toTeamId;
    private String toTeamName;

    private Long companyId;
    private String companyName;
}

