package com.sx.capacity.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 审核拒绝请求体。
 */
@Getter
@Setter
public class RejectTeamChangeBody {

    @NotBlank(message = "拒绝原因不能为空")
    private String reviewReason;
}
