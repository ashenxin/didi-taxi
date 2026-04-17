package com.sx.capacity.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 管理端更新运力公司：仅允许修改公司名称与车队名称。
 */
@Getter
@Setter
public class CompanyUpdateRequest {

    @NotBlank(message = "公司名称不能为空")
    private String companyName;

    @NotBlank(message = "车队名称不能为空")
    private String team;
}
