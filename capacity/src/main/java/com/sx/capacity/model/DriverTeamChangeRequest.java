package com.sx.capacity.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 司机更换所属运力公司（换队）申请单。
 * <p>对应 MySQL 库 {@code capacity}、表 {@code driver_team_change_request}。</p>
 * <p>状态取值与 {@link com.sx.capacity.model.enums.DriverTeamChangeStatus} 一致（如 PENDING/APPROVED/REJECTED）。</p>
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("driver_team_change_request")
public class DriverTeamChangeRequest {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申请司机，对应表 {@code driver} 主键 */
    private Long driverId;

    /** 申请前所属公司，对应表 {@code company} 主键 */
    private Long fromCompanyId;

    /** 目标公司，对应表 {@code company} 主键 */
    private Long toCompanyId;

    /** 流程状态（字符串枚举名，如 PENDING） */
    private String status;

    /** 司机填写的申请原因 */
    private String requestReason;

    /** 发起人标识（工号/账号等，视接入方式） */
    private String requestedBy;

    /** 提交申请时间 */
    private Date requestedAt;

    /** 审核人标识 */
    private String reviewedBy;

    /** 审核完成时间 */
    private Date reviewedAt;

    /** 审核说明（拒绝时多为必填） */
    private String reviewReason;

    /** 记录创建时间 */
    private Date createdAt;

    /** 记录更新时间 */
    private Date updatedAt;

    /** 逻辑删除：0 未删除 */
    private Integer isDeleted;
}
