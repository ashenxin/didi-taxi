package com.sx.order.model.dto;
import com.sx.order.model.DriverTripRecord;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
@Getter @Setter
public class DriverTripView extends DriverTripRecord {
    private BigDecimal finalAmount;
    private BigDecimal discountAmount;
    private BigDecimal payableAmount;
    private BigDecimal paidAmount;
    private String settlementStatus;
    private Integer manualActionRequired;
    private Long billingDurationSeconds;
}
