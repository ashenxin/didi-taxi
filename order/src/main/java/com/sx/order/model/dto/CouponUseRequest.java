package com.sx.order.model.dto;

import java.math.BigDecimal;

public record CouponUseRequest(Long passengerId,
                               Long couponId,
                               String orderNo,
                               BigDecimal discountAmount) {
}
