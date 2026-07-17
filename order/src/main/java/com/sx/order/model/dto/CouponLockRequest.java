package com.sx.order.model.dto;

import java.math.BigDecimal;

public record CouponLockRequest(Long passengerId,
                                String orderNo,
                                Long couponId,
                                BigDecimal finalAmount,
                                Long companyId,
                                String cityCode,
                                String productCode,
                                Boolean manualNoCoupon) {
}
