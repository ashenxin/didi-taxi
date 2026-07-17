package com.sx.order.model.dto;

public record BlockingOrderResult(String blockingOrderNo,
                                  String settlementStatus,
                                  String action) {
}
