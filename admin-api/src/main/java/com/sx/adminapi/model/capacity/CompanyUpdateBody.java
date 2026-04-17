package com.sx.adminapi.model.capacity;

import lombok.Getter;
import lombok.Setter;

/**
 * 管理端更新运力公司：仅公司名称、车队名称；与 capacity {@code CompanyUpdateRequest} 对齐。
 */
@Getter
@Setter
public class CompanyUpdateBody {
    private String companyName;
    private String team;
}
