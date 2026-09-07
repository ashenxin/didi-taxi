package com.sx.driverapi.model.trip;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Data
public class DriverTripView {
    private String id;
    private String orderNo;
    private Long driverId;
    private Long carId;
    private Long companyId;
    private String cityCode;
    private String productCode;
    private String originAddress;
    private String destAddress;
    private Integer status;
    private BigDecimal estimatedAmount;
    private Long distanceMeters;
    private String distanceSource;
    private LocalDateTime orderedAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime arrivedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime cancelledAt;
    private Long serviceDurationSeconds;
    private Integer cancelBy;
    private String cancelReason;
    private LocalDateTime updatedAt;
    private BigDecimal finalAmount;
    private BigDecimal discountAmount;
    private BigDecimal payableAmount;
    private BigDecimal paidAmount;
    private String settlementStatus;
    private Integer manualActionRequired;
    private Long billingDurationSeconds;
}
