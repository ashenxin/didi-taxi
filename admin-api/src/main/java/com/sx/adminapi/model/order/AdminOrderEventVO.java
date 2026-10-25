package com.sx.adminapi.model.order;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.sx.adminapi.common.jackson.FlexibleLocalDateTimeDeserializer;
import com.sx.adminapi.common.jackson.LocalDateTimeDisplaySerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminOrderEventVO {
    private Long id;
    private String orderNo;
    private String eventType;
    private String eventTypeText;
    private Integer fromStatus;
    private String fromStatusText;
    private Integer toStatus;
    private String toStatusText;
    private Integer operatorType;
    private String operatorTypeText;
    private String operatorId;
    private String reasonDesc;
    /** 订单事件原始扩展 JSON；order-service 的字段名为 eventPayload。 */
    @JsonAlias("eventPayload")
    private String extJson;
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeDisplaySerializer.class)
    private LocalDateTime occurredAt;
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeDisplaySerializer.class)
    private LocalDateTime createdAt;
}
