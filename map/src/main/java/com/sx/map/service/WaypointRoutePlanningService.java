package com.sx.map.service;

import com.sx.map.exception.AmapApiException;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.model.dto.RouteRequest;
import com.sx.map.model.dto.RouteResponse;
import com.sx.map.model.dto.WaypointRoutePlanResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * 生成指定途经点路线，并用相同起终点生成无途经点参考路线。
 */
@Service
@Slf4j
public class WaypointRoutePlanningService {

    public enum WaypointAccess {
        ENTRANCE,
        EXIT
    }

    private final AmapDrivingRouteService drivingRouteService;
    private final AmapCoordinateConvertService coordinateConvertService;

    public WaypointRoutePlanningService(AmapDrivingRouteService drivingRouteService,
                                        AmapCoordinateConvertService coordinateConvertService) {
        this.drivingRouteService = drivingRouteService;
        this.coordinateConvertService = coordinateConvertService;
    }

    /**
     * 将页面传入的 WGS84 起终点转换为高德坐标，再使用高德返回的入口或出口作为硬性途经点。
     * 参考路线失败时仍保留途经路线，差值为空。
     */
    public WaypointRoutePlanResponse plan(Point origin,
                                           Point destination,
                                           AmapPoiCandidate waypoint,
                                           WaypointAccess access) {
        requirePoint(origin, "起点");
        requirePoint(destination, "终点");
        if (waypoint == null) {
            throw new AmapApiException("途经地点不能为空");
        }
        if (access == null) {
            throw new AmapApiException("途经点入口类型不能为空");
        }

        Point waypointPoint = switch (access) {
            case ENTRANCE -> waypoint.getEntrancePoint();
            case EXIT -> waypoint.getExitPoint();
        };
        requirePoint(waypointPoint, access == WaypointAccess.ENTRANCE ? "途经地点入口" : "途经地点出口");

        List<Point> convertedEndpoints = coordinateConvertService.convertWgs84ToAmap(List.of(origin, destination));
        Point amapOrigin = convertedEndpoints.get(0);
        Point amapDestination = convertedEndpoints.get(1);
        RouteResponse route = drivingRouteService.drivingRoute(request(amapOrigin, amapDestination, waypointPoint));
        RouteResponse referenceRoute = referenceRoute(amapOrigin, amapDestination);

        WaypointRoutePlanResponse response = new WaypointRoutePlanResponse();
        response.setWaypoint(waypoint);
        response.setWaypointAccess(access.name());
        response.setRoute(route);
        response.setReferenceRoute(referenceRoute);
        if (referenceRoute != null) {
            response.setDistanceDeltaMeters(delta(route.getDistanceMeters(), referenceRoute.getDistanceMeters()));
            response.setDurationDeltaSeconds(delta(route.getDurationSeconds(), referenceRoute.getDurationSeconds()));
        }
        return response;
    }

    private RouteResponse referenceRoute(Point origin, Point destination) {
        try {
            return drivingRouteService.drivingRoute(request(origin, destination, null));
        } catch (AmapApiException | RestClientException e) {
            log.warn("高德参考路线获取失败，保留途经路线并省略差值: {}", e.getMessage());
            return null;
        }
    }

    private static RouteRequest request(Point origin, Point destination, Point waypoint) {
        RouteRequest request = new RouteRequest();
        request.setOrigin(origin);
        request.setDest(destination);
        request.setWaypoint(waypoint);
        return request;
    }

    private static Long delta(Long routeValue, Long referenceValue) {
        if (routeValue == null || referenceValue == null) {
            return null;
        }
        return routeValue - referenceValue;
    }

    private static void requirePoint(Point point, String label) {
        if (point == null || point.getLng() == null || point.getLat() == null) {
            throw new AmapApiException(label + "缺少有效经纬度");
        }
    }
}
