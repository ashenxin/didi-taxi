package com.sx.capacity.model.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 审核通过请求体。
 */
@Getter
@Setter
public class ApproveTeamChangeBody {

    /** 可选备注 */
    private String reviewReason;
}
