package com.sx.map.ai.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 起终点准备结果。地点歧义时 placeChoices 仅包含乘客可见的短时选项摘要，
 * 地图 POI 身份和坐标留在 map-service 内部缓存。
 */
public record RoutePreparationResponse(String status,
                                       EndpointFeedbackView originFeedback,
                                       EndpointFeedbackView destinationFeedback,
                                       Instant expiresAt,
                                       PlaceChoicesView placeChoices) {

    /** 兼容不返回地点选项的既有调用方。 */
    public RoutePreparationResponse(String status, EndpointFeedbackView originFeedback,
                                    EndpointFeedbackView destinationFeedback, Instant expiresAt) {
        this(status, originFeedback, destinationFeedback, expiresAt, null);
    }

    public record EndpointFeedbackView(String status, List<CandidateView> candidates) {
    }

    /** 仅包含可用于向乘客澄清的名称、类型与位置描述，不含地图 ID 或坐标。 */
    public record CandidateView(String name, String type, String city, String district, String address) {
    }

    /** taskNo 与 role 告诉编排层本轮应让乘客选择哪一端，选项本身不携带地图事实。 */
    public record PlaceChoicesView(String taskNo, String role,
                                   List<PlaceChoiceView> choices, Instant expiresAt) {
        public PlaceChoicesView {
            if (taskNo == null || taskNo.isBlank()) {
                throw new IllegalArgumentException("地点选项缺少任务号");
            }
            if (!"ORIGIN".equals(role) && !"DESTINATION".equals(role)) {
                throw new IllegalArgumentException("地点选项角色必须是起点或终点");
            }
            choices = List.copyOf(Objects.requireNonNull(choices, "地点选项不能为空"));
            if (choices.isEmpty()) {
                throw new IllegalArgumentException("地点选项不能为空");
            }
            Objects.requireNonNull(expiresAt, "地点选项缺少有效期");
        }
    }

    /** 仅允许客户端回传 placeChoiceId；名称、类型、城市和地址只用于展示。 */
    public record PlaceChoiceView(String placeChoiceId, String name, String type,
                                  String city, String address, Instant expiresAt) {
        public PlaceChoiceView {
            if (placeChoiceId == null || placeChoiceId.isBlank()
                    || name == null || name.isBlank()) {
                throw new IllegalArgumentException("地点选项缺少编号或名称");
            }
            if ((city == null || city.isBlank()) && (address == null || address.isBlank())) {
                throw new IllegalArgumentException("地点选项缺少可区分的城市或地址");
            }
            Objects.requireNonNull(expiresAt, "地点选项缺少有效期");
        }
    }
}
