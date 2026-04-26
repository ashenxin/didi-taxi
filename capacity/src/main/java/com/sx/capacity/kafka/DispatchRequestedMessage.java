package com.sx.capacity.kafka;

import lombok.Data;

@Data
public class DispatchRequestedMessage {
    private Integer schemaVersion;
    private String eventId;
    private String eventType;
    private String orderNo;
    private String cityCode;
    private String productCode;
    private Origin origin;
    private String createdAt;

    @Data
    public static class Origin {
        private Double lat;
        private Double lng;
    }
}

