package com.sx.capacity.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 管理端创建「公司 + 车队」承运主体。
 * {@link #teamId} 为空时由服务端按全库 MAX(team_id)+1 自动生成（起点 2001）。
 */
@Getter
@Setter
public class CompanyCreateRequest {

    @NotBlank(message = "省份编码不能为空")
    private String provinceCode;

    @NotBlank(message = "省份名称不能为空")
    private String provinceName;

    @NotBlank(message = "城市编码不能为空")
    private String cityCode;

    @NotBlank(message = "城市名称不能为空")
    private String cityName;

    @NotBlank(message = "公司编号不能为空")
    private String companyNo;

    @NotBlank(message = "公司名称不能为空")
    private String companyName;

    @NotBlank(message = "车队名称不能为空")
    private String team;

    /**
     * 车队业务编码，全库唯一；为空则自动生成。
     */
    private Long teamId;
}
