package com.sx.adminapi.model.capacity;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RejectTeamChangeBody {

    @NotBlank(message = "拒绝原因不能为空")
    private String reviewReason;
}
