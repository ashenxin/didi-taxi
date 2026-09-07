package com.sx.order.model.dto;
import java.time.LocalDateTime;
public record DriverTripFilter(long driverId, String status, String dateField,
        LocalDateTime start, LocalDateTime end, long snapshotId, long offset, int limit) {}
