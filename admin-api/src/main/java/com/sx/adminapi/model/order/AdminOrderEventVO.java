package com.sx.adminapi.model.order;

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
    private String extJson;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;
}
