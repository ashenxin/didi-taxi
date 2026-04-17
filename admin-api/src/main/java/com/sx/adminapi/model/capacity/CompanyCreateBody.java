package com.sx.adminapi.model.capacity;

import lombok.Getter;
import lombok.Setter;

/**
 * 管理端创建「公司 + 车队」；与 capacity {@code CompanyCreateRequest} 字段对齐。
 */
@Getter
@Setter
public class CompanyCreateBody {
    private String provinceCode;
    private String provinceName;
    private String cityCode;
    private String cityName;
    private String companyNo;
    private String companyName;
    /** 车队名称 */
    private String team;
    /** 车队业务编码，全库唯一；为空则由下游自动生成 */
    private Long teamId;
}
