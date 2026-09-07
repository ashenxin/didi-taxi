package com.sx.order.model;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.experimental.Accessors;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 司机的一次实际接单服务；改派后保留原司机历史。 */
@Data
@Accessors(chain = true)
@TableName("driver_trip_record")
public class DriverTripRecord {
    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonIgnore
    private Long orderId;
    private String orderNo;
    @JsonIgnore
    private String sourceKey;
    private Long driverId;
    private Long carId;
    private Long companyId;
    private String cityCode;
    private String productCode;
    private String originAddress;
    private String destAddress;
    private Integer status;
    @JsonIgnore
    private Integer activeFlag;
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
}
