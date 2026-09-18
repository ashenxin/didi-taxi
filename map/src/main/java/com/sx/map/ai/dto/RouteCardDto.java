package com.sx.map.ai.dto;

import java.time.Instant;
import java.util.List;

/**
 * 客服路线卡片。数据来自真实地图与通过验证的服务端结果；
 * 默认路线的 via 恒为 null，完整折线仅实时卡片携带。
 */
public record RouteCardDto(String routeRef,
                           PlaceView origin,
                           PlaceView destination,
                           PlaceView via,
                           RouteView route,
                           Instant generatedAt,
                           Instant expiresAt) {

    public record PlaceView(String name) {
    }

    public record RouteView(String coordinateSystem,
                            long distanceMeters,
                            long durationSeconds,
                            List<GeoPointView> polyline) {
    }

    /** 坐标顺序固定为经度、纬度，坐标系为 GCJ02。 */
    public record GeoPointView(double longitude, double latitude) {
    }
}
