package com.sx.capacity.model.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 管理端列表/详情展示：接口字段名与产品文档对齐（team* 对应 company 维度）。
 */
@Getter
@Setter
@Accessors(chain = true)
public class DriverTeamChangeRequestVO {

    private Long id;
    private String status;
    private Date requestedAt;
    private String requestedBy;
    private Date reviewedAt;
    private String reviewedBy;
    /** 审核备注；拒绝时为主要原因 */
    private String reviewReason;

    private Long driverId;
    private String driverName;
    private String driverPhone;
    /** 司机档案城市编码（管理端数据域校验） */
    private String driverCityCode;

    /** 对应 from_company_id */
    private Long fromTeamId;
    private String fromTeamName;

    /** 对应 to_company_id */
    private Long toTeamId;
    private String toTeamName;

    /** 目标运力主体公司信息（由 to 推导） */
    private Long companyId;
    private String companyName;

    /** 司机申请说明 */
    private String requestReason;
}
