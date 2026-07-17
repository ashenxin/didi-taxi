package com.sx.passengerapi.model.settlement;

import java.math.BigDecimal;
import java.util.List;

public record SettlementDetailVO(String settlementStatus,
                                 BigDecimal originalFare,
                                 BigDecimal discountAmount,
                                 BigDecimal payableAmount,
                                 BigDecimal paidAmount,
                                 String couponName,
                                 List<String> availableChannels,
                                 String message) {
}
