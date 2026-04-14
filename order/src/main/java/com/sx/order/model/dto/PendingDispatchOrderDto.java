package com.sx.order.model.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 待派单（CREATED）订单摘要：供运力服务做迟滞匹配与 GEO 指派。
 */
@Getter
@Setter
public class PendingDispatchOrderDto {

    private String orderNo;
    private String cityCode;
    private String productCode;
    private BigDecimal originLat;
    private BigDecimal originLng;
}
