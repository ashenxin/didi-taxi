package com.sx.driverapi.model.capacity;

import lombok.Getter;
import lombok.Setter;

/**
 * 与运力 {@code GET /api/v1/drivers/{id}} 返回的 data(JSON) 字段对齐的最小子集。
 */
@Getter
@Setter
public class CapacityDriverDetail {
    private Long id;
    private String cityCode;
    private String cityName;
    private Long companyId;
    private Integer canAcceptOrder;
    private Integer monitorStatus;
}

