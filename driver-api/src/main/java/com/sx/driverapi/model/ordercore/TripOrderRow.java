package com.sx.driverapi.model.ordercore;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 与 order-service {@code TripOrder} JSON 对齐。 */
@Getter
@Setter
public class TripOrderRow {
    private Long id;
    private String orderNo;
    private Long passengerId;
    private Long driverId;
    private Long carId;
    private Long companyId;
    private String productCode;
    private String provinceCode;
    private String cityCode;
    private String originAddress;
    private BigDecimal originLat;
    private BigDecimal originLng;
    private String destAddress;
    private BigDecimal destLat;
    private BigDecimal destLng;
    private Integer status;
    private BigDecimal estimatedAmount;
    private BigDecimal finalAmount;
    private Long fareRuleId;
    private String fareRuleSnapshot;
    private Integer cancelBy;
    private String cancelReason;
    private LocalDateTime createdAt;
    private LocalDateTime assignedAt;
    private LocalDateTime offerExpiresAt;
    private Integer offerRound;
    private LocalDateTime lastOfferAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime arrivedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime cancelledAt;
    private LocalDateTime updatedAt;
    private Integer isDeleted;
}
