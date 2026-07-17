package com.sx.passengerapi.model.ordercore;

import java.math.BigDecimal;

public record SettlementSummaryRow(String orderNo, String settlementStatus,
                                   BigDecimal finalAmount, BigDecimal payableAmount,
                                   BigDecimal paidAmount) {
}
