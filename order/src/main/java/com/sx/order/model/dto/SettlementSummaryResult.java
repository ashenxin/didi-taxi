package com.sx.order.model.dto;

import java.math.BigDecimal;

public record SettlementSummaryResult(String orderNo, String settlementStatus,
                                      BigDecimal finalAmount, BigDecimal payableAmount,
                                      BigDecimal paidAmount) {
}
