package com.sx.map.ai.service;

import com.sx.map.ai.model.PassengerRouteSnapshot;
import com.sx.map.exception.AmapApiException;
import com.sx.map.exception.AmapRouteNotFoundException;
import com.sx.map.model.dto.Point;
import com.sx.map.model.dto.RouteRequest;
import com.sx.map.model.dto.RouteResponse;
import com.sx.map.service.AmapDrivingRouteService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 为 AI 客服会话建立一条可供后续核查的真实默认驾车路线。
 *
 * 调用方必须先核对乘客与会话，并提供当前可信条件版本。本类只从 map-service
 * 尚有效的已确认条件缓存取得 GCJ02 起终点，不从地图页、聊天摘要或模型输出读取地点。
 * 本类不替乘客确认起终点。
 * 高德调用在会话数据库事务外执行；只有完整路线快照写入地图缓存成功后才返回结果。
 * 是否把路线卡片作为客服消息持久化，由后续对话编排负责；写消息前仍需复核活动请求
 * 和条件版本，不能把本类的缓存复核当作数据库事务中的最终校验。
 */
@Service
public class PassengerDefaultRouteService {
    private static final Duration SNAPSHOT_LIFETIME = Duration.ofMinutes(5);
    private static final String AMAP_PROVIDER = "gaode";

    private final AmapDrivingRouteService drivingRouteService;
    private final PassengerRouteConditionsCache conditionsCache;
    private final PassengerRouteSnapshotCache snapshotCache;

    public PassengerDefaultRouteService(AmapDrivingRouteService drivingRouteService,
                                        PassengerRouteConditionsCache conditionsCache,
                                        PassengerRouteSnapshotCache snapshotCache) {
        this.drivingRouteService = drivingRouteService;
        this.conditionsCache = conditionsCache;
        this.snapshotCache = snapshotCache;
    }

    /**
     * 使用高德返回的第一条默认方案；不能因它缺少核查证据而静默换成另一条路线。
     * 返回的完整快照仅供 map-service 内部组装卡片，不能整体交给模型或客户端。
     */
    public PassengerRouteSnapshot createAndCache(long customerId,
                                                 String conversationNo,
                                                 long conditionVersion) {
        if (customerId <= 0 || conversationNo == null || conversationNo.isBlank()
                || conditionVersion <= 0) {
            throw new IllegalArgumentException("缺少可信乘客会话或条件版本");
        }
        PassengerRouteConditionsCache.ConfirmedConditions confirmed = conditionsCache
                .find(customerId, conversationNo, conditionVersion)
                .orElseThrow(() -> new IllegalStateException("已确认起终点不存在或已失效"));
        PassengerRouteSnapshot.Place origin = confirmed.origin();
        PassengerRouteSnapshot.Place destination = confirmed.destination();

        RouteRequest request = new RouteRequest();
        request.setOrigin(toRoutePoint(origin.coordinate()));
        request.setDest(toRoutePoint(destination.coordinate()));
        request.setWaypoints(List.of());

        List<AmapDrivingRouteService.DrivingRouteOption> options = drivingRouteService.drivingRoutes(request);
        if (options == null || options.isEmpty() || options.getFirst() == null) {
            throw new AmapRouteNotFoundException("高德未返回可用的默认路线");
        }
        AmapDrivingRouteService.DrivingRouteOption selected = options.getFirst();
        RouteResponse route = selected.route();
        if (route == null || !AMAP_PROVIDER.equals(route.getProvider())
                || route.getDistanceMeters() == null || route.getDurationSeconds() == null
                || route.getDistanceMeters() < 0 || route.getDurationSeconds() < 0) {
            throw new AmapApiException("默认路线缺少可信地图来源、里程或预计时间");
        }

        List<PassengerRouteSnapshot.GeoPoint> polyline = toSnapshotPoints(route.getPolyline());
        if (polyline.size() < 2 || selected.steps() == null || selected.steps().isEmpty()) {
            throw new AmapApiException("默认路线缺少完整折线或导航步骤");
        }
        List<PassengerRouteSnapshot.NavigationStep> navigationSteps = new ArrayList<>(selected.steps().size());
        for (AmapDrivingRouteService.NavigationStep step : selected.steps()) {
            if (step == null) {
                throw new AmapApiException("默认路线包含空导航步骤");
            }
            List<PassengerRouteSnapshot.GeoPoint> stepPolyline = toSnapshotPoints(step.polyline());
            if (stepPolyline.isEmpty()) {
                throw new AmapApiException("默认路线导航步骤缺少折线");
            }
            navigationSteps.add(new PassengerRouteSnapshot.NavigationStep(
                    step.instruction(), step.action(), step.assistantAction(),
                    step.orientation(), step.roadName(), stepPolyline));
        }

        // 高德调用可能跨越条件变更或缓存失效；不能把旧路线写成新条件下的客服卡片。
        PassengerRouteConditionsCache.ConfirmedConditions current = conditionsCache
                .find(customerId, conversationNo, conditionVersion)
                .orElseThrow(() -> new IllegalStateException("算路期间已确认起终点失效或变更"));
        if (!confirmed.equals(current)) {
            throw new IllegalStateException("算路期间已确认起终点发生变更");
        }

        Instant generatedAt = Instant.now();
        PassengerRouteSnapshot snapshot = new PassengerRouteSnapshot(
                snapshotCache.newRouteRef(), customerId, conversationNo, conditionVersion,
                origin, destination, route.getProvider(),
                PassengerRouteSnapshot.COORDINATE_SYSTEM_GCJ02,
                PassengerRouteSnapshot.TRAVEL_MODE_DRIVING,
                PassengerRouteSnapshot.RouteKind.DEFAULT,
                polyline, navigationSteps, route.getDistanceMeters(), route.getDurationSeconds(),
                generatedAt, generatedAt.plus(SNAPSHOT_LIFETIME),
                null, List.of(), null);
        snapshotCache.save(snapshot);
        return snapshot;
    }

    private static Point toRoutePoint(PassengerRouteSnapshot.GeoPoint coordinate) {
        Point point = new Point();
        point.setLng(coordinate.longitude());
        point.setLat(coordinate.latitude());
        return point;
    }

    private static List<PassengerRouteSnapshot.GeoPoint> toSnapshotPoints(List<Point> points) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        List<PassengerRouteSnapshot.GeoPoint> converted = new ArrayList<>(points.size());
        for (Point point : points) {
            if (point == null || point.getLng() == null || point.getLat() == null) {
                throw new AmapApiException("默认路线折线包含无效坐标");
            }
            try {
                converted.add(new PassengerRouteSnapshot.GeoPoint(point.getLng(), point.getLat()));
            } catch (IllegalArgumentException e) {
                throw new AmapApiException("默认路线折线包含无效坐标");
            }
        }
        return List.copyOf(converted);
    }
}
