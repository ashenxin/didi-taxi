package com.sx.capacity.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * App/司机端提交换队申请请求体（capacity 内部接口使用）。
 */
@Getter
@Setter
public class AppSubmitTeamChangeBody {
    @NotNull(message = "driverId不能为空")
    private Long driverId;
    /** 目标运力主体（company.id）；产品文档中称 toCompanyId/toTeamId */
    @NotNull(message = "toTeamId不能为空")
    private Long toTeamId;
    /** 可选 0-200 */
    private String reason;
    /** 由上游（driver-api）注入 */
    private String requestedBy;
}

