package com.sx.adminapi.model.capacity;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 与 capacity-service 中换队申请展示字段对齐。
 */
@Getter
@Setter
@Accessors(chain = true)
public class AdminDriverTeamChangeRequestVO {

    private Long id;
    private String status;
    private Date requestedAt;
    private String requestedBy;
    private Date reviewedAt;
    private String reviewedBy;
    private String reviewReason;

    private Long driverId;
    private String driverName;
    private String driverPhone;
    /** 司机档案城市编码；BFF 用于 {@link com.sx.adminapi.security.AdminDataScope#assertDriverCityReadable}，不对前端展示作硬性要求。 */
    private String driverCityCode;

    private Long fromTeamId;
    private String fromTeamName;

    private Long toTeamId;
    private String toTeamName;

    private Long companyId;
    private String companyName;

    private String requestReason;
}
