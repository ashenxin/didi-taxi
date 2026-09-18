package com.sx.map.ai.dto;

import java.time.Instant;
import java.util.List;

/** 待确认起终点准备结果；status 为 READY_FOR_CONFIRMATION 或 NEEDS_CLARIFICATION。 */
public record RoutePreparationResponse(String status,
                                       EndpointFeedbackView originFeedback,
                                       EndpointFeedbackView destinationFeedback,
                                       Instant expiresAt) {

    public record EndpointFeedbackView(String status, List<CandidateView> candidates) {
    }

    /** 仅包含可用于向乘客澄清的名称、类型与位置描述，不含地图 ID 或坐标。 */
    public record CandidateView(String name, String type, String city, String district, String address) {
    }
}
