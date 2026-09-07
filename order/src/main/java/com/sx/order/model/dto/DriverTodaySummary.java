package com.sx.order.model.dto;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
@Data
public class DriverTodaySummary {
    private LocalDate businessDate;
    private String timezone;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    private LocalDateTime asOf;
    private long completedCount;
    private long cancelledCount;
    private long driverCancelledCount;
    private long passengerCancelledCount;
    private long systemCancelledCount;
    private long unpricedCount;
    private long abnormalSettlementCount;
    private long missingDistanceCount;
    private long missingDurationCount;
    private BigDecimal orderAmount;
    private long distanceMeters;
    private long serviceDurationSeconds;
    private DriverTripView latestTrip;
}
