package com.sx.map.ai.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 乘客 AI 客服在 map-service 内部保存的一条真实驾车路线。
 *
 * 此对象含地图地点身份、完整折线及导航步骤，只能进入地图服务管理的短时缓存。
 * 客服消息只保存展示摘要；模型和客户端均不能提交或覆盖本对象中的地图事实。
 * 缓存读取方还必须核对乘客、会话、条件版本及失效时间，构造成功不代表授权读取。
 */
public record PassengerRouteSnapshot(
        String routeRef,
        long customerId,
        String conversationNo,
        long conditionVersion,
        Place origin,
        Place destination,
        String provider,
        String coordinateSystem,
        String travelMode,
        RouteKind routeKind,
        List<GeoPoint> polyline,
        List<NavigationStep> navigationSteps,
        long distanceMeters,
        long durationSeconds,
        Instant generatedAt,
        Instant expiresAt,
        ViaPlace via,
        List<GeoPoint> routingWaypoints,
        PassageVerification passageVerification
) {
    public static final String COORDINATE_SYSTEM_GCJ02 = "GCJ02";
    public static final String TRAVEL_MODE_DRIVING = "DRIVING";

    public PassengerRouteSnapshot {
        requireText(routeRef, "路线引用");
        if (customerId <= 0) {
            throw new IllegalArgumentException("乘客ID必须为正数");
        }
        requireText(conversationNo, "会话编号");
        if (conditionVersion <= 0) {
            throw new IllegalArgumentException("路线条件版本必须为正数");
        }
        Objects.requireNonNull(origin, "起点不能为空");
        Objects.requireNonNull(destination, "终点不能为空");
        requireText(provider, "地图提供方");
        if (!COORDINATE_SYSTEM_GCJ02.equals(coordinateSystem)) {
            throw new IllegalArgumentException("客服路线坐标系必须为GCJ02");
        }
        if (!TRAVEL_MODE_DRIVING.equals(travelMode)) {
            throw new IllegalArgumentException("客服路线只支持驾车");
        }
        Objects.requireNonNull(routeKind, "路线类型不能为空");
        polyline = requirePoints(polyline, "完整路线折线");
        if (polyline.size() < 2) {
            throw new IllegalArgumentException("完整路线折线至少需要两个点");
        }
        navigationSteps = requireNonEmpty(navigationSteps, "导航步骤");
        if (distanceMeters < 0 || durationSeconds < 0) {
            throw new IllegalArgumentException("路线里程和预计时间不能为负数");
        }
        Objects.requireNonNull(generatedAt, "路线生成时间不能为空");
        Objects.requireNonNull(expiresAt, "路线失效时间不能为空");
        if (!expiresAt.isAfter(generatedAt)) {
            throw new IllegalArgumentException("路线失效时间必须晚于生成时间");
        }
        routingWaypoints = routingWaypoints == null ? List.of() : List.copyOf(routingWaypoints);
        if (routeKind == RouteKind.DEFAULT) {
            if (via != null || !routingWaypoints.isEmpty() || passageVerification != null) {
                throw new IllegalArgumentException("默认路线不能附带指定途经点验证");
            }
        } else if (via == null || routingWaypoints.isEmpty() || passageVerification == null) {
            throw new IllegalArgumentException("指定途经点路线必须包含地点、算路点和通过证据");
        }
    }

    public enum RouteKind {
        DEFAULT, VIA_PLACE
    }

    /**
     * 坐标顺序固定为经度、纬度，且只承载已经转换为 GCJ02 的地图坐标。
     */
    public record GeoPoint(double longitude, double latitude) {
        public GeoPoint {
            if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180
                    || !Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
                throw new IllegalArgumentException("无效的地图经纬度");
            }
        }
    }

    /**
     * 已由地图服务确认的地点。placeId 仅在地图提供方实际返回时保存。
     */
    public record Place(String placeId, String name, String address, String city, GeoPoint coordinate) {
        public Place {
            requireText(name, "地点名称");
            if (isBlank(address) && isBlank(city)) {
                throw new IllegalArgumentException("地点必须包含地址或城市");
            }
            Objects.requireNonNull(coordinate, "地点坐标不能为空");
        }
    }

    /**
     * 只记录本次指定的收费站、服务区或加油站；导航点由可信地图数据提供。
     * 收费站缺少入口或出口时可没有导航点，由 routingWaypoints 记录实际算路坐标；
     * POI 中心坐标不能自动充当经过证据，仍须有同一路线的导航事件验证通过。
     */
    public record ViaPlace(Place place, BusinessType businessType, List<GeoPoint> navigationPoints) {
        public ViaPlace {
            Objects.requireNonNull(place, "途经地点不能为空");
            Objects.requireNonNull(businessType, "途经地点类型不能为空");
            navigationPoints = navigationPoints == null ? List.of() : List.copyOf(navigationPoints);
            if (businessType != BusinessType.TOLL_STATION && navigationPoints.isEmpty()) {
                throw new IllegalArgumentException("服务区或加油站必须包含可信导航点");
            }
        }
    }

    public enum BusinessType {
        TOLL_STATION, SERVICE_AREA, GAS_STATION
    }

    /**
     * 同一条路线的分段导航事实，供以后针对新的地点重新核查。
     */
    public record NavigationStep(String instruction, String action, String assistantAction,
                                 String orientation, String roadName, List<GeoPoint> polyline) {
        public NavigationStep {
            instruction = emptyIfNull(instruction);
            action = emptyIfNull(action);
            assistantAction = emptyIfNull(assistantAction);
            orientation = emptyIfNull(orientation);
            roadName = emptyIfNull(roadName);
            polyline = polyline == null ? List.of() : List.copyOf(polyline);
        }
    }

    /**
     * 仅证明本次 VIA_PLACE 规划的指定地点；不能复用为以后其他地点的结论。
     * 具体证据原因由地图服务的 Java 校验规则产生。
     */
    public record PassageVerification(String verdict, String reason) {
        public PassageVerification {
            if (!"PASSED".equals(verdict)) {
                throw new IllegalArgumentException("指定途经点路线必须已经验证通过");
            }
            requireText(reason, "经过验证原因");
        }
    }

    private static <T> List<T> requireNonEmpty(List<T> values, String field) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return List.copyOf(values);
    }

    private static List<GeoPoint> requirePoints(List<GeoPoint> points, String field) {
        return requireNonEmpty(points, field);
    }

    private static void requireText(String value, String field) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
