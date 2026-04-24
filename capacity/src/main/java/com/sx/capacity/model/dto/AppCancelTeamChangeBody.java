package com.sx.capacity.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * App/司机端撤销/放弃换队并恢复接单请求体（capacity 内部接口使用）。
 */
@Getter
@Setter
public class AppCancelTeamChangeBody {
    @NotNull(message = "driverId不能为空")
    private Long driverId;
    /** 由上游（driver-api）注入 */
    private String requestedBy;
}

