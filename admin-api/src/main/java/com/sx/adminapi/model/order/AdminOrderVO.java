package com.sx.adminapi.model.order;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.sx.adminapi.common.jackson.FlexibleLocalDateTimeDeserializer;
import com.sx.adminapi.common.jackson.LocalDateTimeDisplaySerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AdminOrderVO {
    private Long id;
    private String orderNo;
    private Long passengerId;
    private String passengerPhone;
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
    private String statusText;
    private BigDecimal estimatedAmount;
    private BigDecimal finalAmount;
    private Long fareRuleId;
    private String fareRuleSnapshot;
    private Integer cancelBy;
    private String cancelReason;
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeDisplaySerializer.class)
    private LocalDateTime createdAt;
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeDisplaySerializer.class)
    private LocalDateTime assignedAt;
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeDisplaySerializer.class)
    private LocalDateTime acceptedAt;
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeDisplaySerializer.class)
    private LocalDateTime arrivedAt;
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeDisplaySerializer.class)
    private LocalDateTime startedAt;
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeDisplaySerializer.class)
    private LocalDateTime finishedAt;
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeDisplaySerializer.class)
    private LocalDateTime cancelledAt;
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeDisplaySerializer.class)
    private LocalDateTime updatedAt;
    private Integer isDeleted;
}
