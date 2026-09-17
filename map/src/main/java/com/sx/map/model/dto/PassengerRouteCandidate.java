package com.sx.map.model.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 已验证经过指定地点的一条完整路线方案。
 * 一个对象只对应一个已确认的真实POI和一条高德路线；规划服务负责验证实际经过。
 *
 * 本对象是map-service输出给应用层的业务DTO，不是数据库实体，也不是模型长期记忆。
 * 完整折线供前端绘图和服务端保存快照，模型只能读取由Tool生成的轻量摘要。
 *
 * @param routeCandidateId  服务端生成的不透明内部ID，不表示乘客必须再次选择路线。
 * @param waypoint          本路线实际经过的高德POI快照。
 * @param routingWaypoints  请求高德算路时使用的有序坐标；收费站可能仅使用POI中心，坐标本身不是经过证据。
 * @param passageMode       受控途经方式，用于解释本次如何验证“经过”。
 * @param route             经过指定地点的完整路线。
 * @param passageVerification Java确定性规则给出的经过验证原因，不允许由模型自行填写。
 */
public record PassengerRouteCandidate(String routeCandidateId, AmapPoiCandidate waypoint,
                                      List<Point> routingWaypoints, String passageMode, RouteResponse route,
                                      String passageVerification, Instant generatedAt, Instant expiresAt) {

    /**
     * 服务区或高速加油站按入口、出口顺序完整通过。
     */
    public static final String PASSAGE_MODE_ENTER_AND_EXIT = "ENTER_AND_EXIT";

    /**
     * 收费站按地图导航步骤中的收费站到达动作验证通过。
     */
    public static final String PASSAGE_MODE_PASS_TOLL_CHANNEL = "PASS_TOLL_CHANNEL";

    private static final Set<String> SUPPORTED_PASSAGE_MODES = Set.of(
            PASSAGE_MODE_ENTER_AND_EXIT,
            PASSAGE_MODE_PASS_TOLL_CHANNEL
    );

    public PassengerRouteCandidate(String routeCandidateId,
                                   AmapPoiCandidate waypoint,
                                   List<Point> routingWaypoints,
                                   String passageMode,
                                   RouteResponse route,
                                   String passageVerification,
                                   Instant generatedAt,
                                   Instant expiresAt) {
        if (routeCandidateId == null || routeCandidateId.isBlank()) {
            throw new IllegalArgumentException("路线候选ID不能为空");
        }
        this.routeCandidateId = routeCandidateId;
        this.waypoint = Objects.requireNonNull(waypoint, "途经POI不能为空");
        if (routingWaypoints == null || routingWaypoints.isEmpty()) {
            throw new IllegalArgumentException("算路坐标不能为空");
        }
        this.routingWaypoints = List.copyOf(routingWaypoints);
        if (passageMode == null || passageMode.isBlank()) {
            throw new IllegalArgumentException("途经验证方式不能为空");
        }
        if (!SUPPORTED_PASSAGE_MODES.contains(passageMode)) {
            throw new IllegalArgumentException("不支持的途经验证方式: " + passageMode);
        }
        this.passageMode = passageMode;
        this.route = Objects.requireNonNull(route, "候选路线不能为空");
        if (passageVerification == null || passageVerification.isBlank()) {
            throw new IllegalArgumentException("经过验证说明不能为空");
        }
        this.passageVerification = passageVerification;
        this.generatedAt = Objects.requireNonNull(generatedAt, "候选生成时间不能为空");
        this.expiresAt = Objects.requireNonNull(expiresAt, "候选失效时间不能为空");
        if (!expiresAt.isAfter(generatedAt)) {
            throw new IllegalArgumentException("候选失效时间必须晚于生成时间");
        }
    }

}
