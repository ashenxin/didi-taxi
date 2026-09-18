package com.sx.map.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.map.config.AmapProperties;
import com.sx.map.exception.AmapApiException;
import com.sx.map.exception.AmapRouteNotFoundException;
import com.sx.map.model.dto.Point;
import com.sx.map.model.dto.RouteRequest;
import com.sx.map.model.dto.RouteResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 高德「驾车路径规划」Web 服务：{@code /v3/direction/driving}。
 *
 * 高德文档地址：https://lbs.amap.com/api/webservice/guide/api/direction
 * v3为经典接口，参数为origin/destination经纬度字符串。
 */
@Service
@Slf4j
public class AmapDrivingRouteService {

    private static final String DRIVING_PATH = "/v3/direction/driving";
    private static final int MAX_WAYPOINTS = 16;

    private final RestClient amapRestClient;
    private final AmapProperties amapProperties;
    private final ObjectMapper objectMapper;

    public AmapDrivingRouteService(RestClient amapRestClient, AmapProperties amapProperties, ObjectMapper objectMapper) {
        this.amapRestClient = amapRestClient;
        this.amapProperties = amapProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 请求高德驾车路径并返回第一条方案。
     *
     * 这个方法保留给只需要高德推荐路线的既有调用。需要比较备选路线、判断掉头或校验
     * 服务区方向的代码必须调用 {@link #drivingRoutes(RouteRequest)}，避免丢失其他path和
     * 分段导航动作。
     */
    public RouteResponse drivingRoute(RouteRequest request) {
        return drivingRoutes(request).getFirst().route();
    }

    /**
     * 请求高德驾车路径并解析全部path。
     *
     * {@link RouteRequest#getWaypoints()} 中的坐标严格按原顺序发送。服务区场景传入
     * “入口、出口”后，高德必须先到入口再到出口；地图适配器不能为了距离更短而调整顺序。
     * 返回结果同时保留导航动作，供上层确定性代码判断途经点附近是否发生掉头或折返。
     */
    public List<DrivingRouteOption> drivingRoutes(RouteRequest request) {
        if (request == null) {
            throw new AmapApiException("路线请求不能为空");
        }
        if (amapProperties.getKey() == null || amapProperties.getKey().isBlank()) {
            throw new AmapApiException("未配置高德 Key：请设置环境变量 AMAP_KEY / MAP_AMAP_KEY，或 JVM 参数 -DAMAP_KEY / -Dmap.amap.key");
        }
        String origin = toAmapCoord(request.getOrigin());
        String destination = toAmapCoord(request.getDest());
        List<Point> waypoints = request.getWaypoints();
        if (waypoints.size() > MAX_WAYPOINTS) {
            throw new AmapApiException("高德驾车路线最多支持" + MAX_WAYPOINTS + "个途经点");
        }
        UriComponentsBuilder uriBuilder = UriComponentsBuilder
                .fromUriString(amapProperties.getBaseUrl())
                .path(DRIVING_PATH)
                .queryParam("key", amapProperties.getKey())
                .queryParam("origin", origin)
                .queryParam("destination", destination)
                // strategy=10允许高德按推荐策略返回多条方案；业务层先过滤方向，再形成路线候选。
                .queryParam("strategy", 10)
                // extensions=all用于取得step折线和导航动作，方向判断不能只看总距离。
                .queryParam("extensions", "all");
        if (!waypoints.isEmpty()) {
            String orderedWaypoints = waypoints.stream()
                    .map(AmapDrivingRouteService::toAmapCoord)
                    .collect(Collectors.joining(";"));
            uriBuilder.queryParam("waypoints", orderedWaypoints);
        }
        URI uri = uriBuilder.build(true)
                .toUri();

        String raw = amapRestClient.get()
                .uri(uri)
                .retrieve()
                .body(String.class);
        if (raw == null || raw.isBlank()) {
            throw new AmapApiException("高德返回空响应");
        }

        try {
            JsonNode root = objectMapper.readTree(raw);
            String status = root.path("status").asText("");
            if (!"1".equals(status)) {
                String info = root.path("info").asText("unknown");
                throw new AmapApiException("高德路径规划失败: " + info);
            }
            JsonNode paths = root.path("route").path("paths");
            if (!paths.isArray()) {
                throw new AmapApiException("高德路线响应缺少 paths 数组");
            }
            if (paths.isEmpty()) {
                throw new AmapRouteNotFoundException("高德未返回可用路线（paths 为空）");
            }
            List<DrivingRouteOption> options = new ArrayList<>(paths.size());
            for (JsonNode path : paths) {
                long distanceMeters = parseRequiredLongField(path, "distance");
                long durationSeconds = parseRequiredLongField(path, "duration");
                List<NavigationStep> steps = parseNavigationSteps(path.path("steps"));

                RouteResponse response = new RouteResponse();
                response.setDistanceMeters(distanceMeters);
                response.setDurationSeconds(durationSeconds);
                response.setPolyline(mergePolyline(steps));
                response.setProvider("gaode");
                response.setTraceId(UUID.randomUUID().toString());
                options.add(new DrivingRouteOption(response, steps));
            }
            log.info("高德驾车路线成功 pathCount={} firstDistanceM={} firstDurationS={}",
                    options.size(),
                    options.getFirst().route().getDistanceMeters(),
                    options.getFirst().route().getDurationSeconds());
            return List.copyOf(options);
        } catch (AmapApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("高德驾车路线响应解析失败", e);
            throw new AmapApiException("解析高德响应失败: " + e.getMessage());
        }
    }

    /**
     * 高德要求：{@code 经度,纬度}（与常见 lat,lng 顺序不同）。
     */
    private static String toAmapCoord(Point p) {
        if (p == null || p.getLng() == null || p.getLat() == null) {
            throw new AmapApiException("路线坐标缺少有效经纬度");
        }
        return p.getLng() + "," + p.getLat();
    }

    private static long parseRequiredLongField(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isMissingNode() || n.isNull()) {
            throw new AmapApiException("高德路线缺少" + field + "字段");
        }
        if (n.isNumber()) {
            return n.longValue();
        }
        String s = n.asText("").trim();
        if (s.isEmpty()) {
            throw new AmapApiException("高德路线" + field + "字段为空");
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new AmapApiException("高德路线" + field + "字段不是有效整数");
        }
    }

    private static List<NavigationStep> parseNavigationSteps(JsonNode steps) {
        if (!steps.isArray() || steps.isEmpty()) {
            return List.of();
        }
        List<NavigationStep> result = new ArrayList<>(steps.size());
        for (JsonNode step : steps) {
            result.add(new NavigationStep(
                    step.path("instruction").asText(""),
                    step.path("action").asText(""),
                    step.path("assistant_action").asText(""),
                    step.path("orientation").asText(""),
                    firstNonBlank(step.path("road_name").asText(""), step.path("road").asText("")),
                    parsePolyline(step.path("polyline").asText(""))
            ));
        }
        return List.copyOf(result);
    }

    private static List<Point> mergePolyline(List<NavigationStep> steps) {
        List<Point> points = new ArrayList<>();
        for (NavigationStep step : steps) {
            for (Point point : step.polyline()) {
                if (!isSamePoint(points, point)) {
                    points.add(point);
                }
            }
        }
        return List.copyOf(points);
    }

    private static List<Point> parsePolyline(String polyline) {
        if (polyline == null || polyline.isBlank()) {
            return List.of();
        }
        List<Point> points = new ArrayList<>();
        for (String coordinate : polyline.split(";")) {
            Point point = parsePoint(coordinate);
            if (point != null && !isSamePoint(points, point)) {
                points.add(point);
            }
        }
        return List.copyOf(points);
    }

    private static Point parsePoint(String coordinate) {
        String[] parts = coordinate.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            Point point = new Point();
            point.setLng(Double.parseDouble(parts[0].trim()));
            point.setLat(Double.parseDouble(parts[1].trim()));
            return point;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isSamePoint(List<Point> points, Point candidate) {
        if (points.isEmpty()) {
            return false;
        }
        Point previous = points.getLast();
        return Double.compare(previous.getLng(), candidate.getLng()) == 0
                && Double.compare(previous.getLat(), candidate.getLat()) == 0;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    /**
     * 高德单条path及其导航步骤。
     *
     * {@link RouteResponse} 继续作为前端可消费的通用路线结果；步骤只服务于本次方向校验，
     * 不会进入模型上下文，也不要求前端理解高德的导航动作文本。
     */
    public record DrivingRouteOption(RouteResponse route, List<NavigationStep> steps) {

        public DrivingRouteOption {
            if (route == null) {
                throw new IllegalArgumentException("路线结果不能为空");
            }
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    /**
     * 高德路线分段中与方向判断相关的最小字段集合。
     */
    public record NavigationStep(String instruction,
                                 String action,
                                 String assistantAction,
                                 String orientation,
                                 String roadName,
                                 List<Point> polyline) {

        public NavigationStep {
            instruction = instruction == null ? "" : instruction;
            action = action == null ? "" : action;
            assistantAction = assistantAction == null ? "" : assistantAction;
            orientation = orientation == null ? "" : orientation;
            roadName = roadName == null ? "" : roadName;
            polyline = polyline == null ? List.of() : List.copyOf(polyline);
        }
    }
}
