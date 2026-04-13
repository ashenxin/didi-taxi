package com.sx.map.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.map.config.AmapProperties;
import com.sx.map.exception.AmapApiException;
import com.sx.map.model.dto.Point;
import com.sx.map.model.dto.RouteRequest;
import com.sx.map.model.dto.RouteResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * 高德「驾车路径规划」Web 服务：{@code /v3/direction/driving}。
 *
 * <p>文档：<a href="https://lbs.amap.com/api/webservice/guide/api/direction">路径规划 2.0</a>
 * （v3 为经典接口，参数为 origin/destination 经纬度字符串）。</p>
 */
@Service
@Slf4j
public class AmapDrivingRouteService {

    private static final String DRIVING_PATH = "/v3/direction/driving";

    private final RestClient amapRestClient;
    private final AmapProperties amapProperties;
    private final ObjectMapper objectMapper;

    public AmapDrivingRouteService(RestClient amapRestClient, AmapProperties amapProperties, ObjectMapper objectMapper) {
        this.amapRestClient = amapRestClient;
        this.amapProperties = amapProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 请求高德驾车路径，解析首条 path 的里程与时长。
     */
    public RouteResponse drivingRoute(RouteRequest request) {
        if (amapProperties.getKey() == null || amapProperties.getKey().isBlank()) {
            throw new AmapApiException("未配置高德 Key：请设置环境变量 AMAP_KEY / MAP_AMAP_KEY，或 JVM 参数 -DAMAP_KEY / -Dmap.amap.key");
        }
        String origin = toAmapCoord(request.getOrigin());
        String destination = toAmapCoord(request.getDest());
        URI uri = UriComponentsBuilder
                .fromUriString(amapProperties.getBaseUrl())
                .path(DRIVING_PATH)
                .queryParam("key", amapProperties.getKey())
                .queryParam("origin", origin)
                .queryParam("destination", destination)
                .build(true)
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
            if (!paths.isArray() || paths.isEmpty()) {
                throw new AmapApiException("高德未返回可用路线（paths 为空）");
            }
            JsonNode first = paths.get(0);
            long distanceMeters = parseLongField(first, "distance");
            long durationSeconds = parseLongField(first, "duration");

            RouteResponse resp = new RouteResponse();
            resp.setDistanceMeters(distanceMeters);
            resp.setDurationSeconds(durationSeconds);
            resp.setProvider("gaode");
            resp.setTraceId(UUID.randomUUID().toString());
            log.info("amap driving route ok distanceM={} durationS={}", distanceMeters, durationSeconds);
            return resp;
        } catch (AmapApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("amap driving route parse failed", e);
            throw new AmapApiException("解析高德响应失败: " + e.getMessage());
        }
    }

    /**
     * 高德要求：{@code 经度,纬度}（与常见 lat,lng 顺序不同）。
     */
    private static String toAmapCoord(Point p) {
        return p.getLng() + "," + p.getLat();
    }

    private static long parseLongField(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isMissingNode() || n.isNull()) {
            return 0L;
        }
        if (n.isNumber()) {
            return n.longValue();
        }
        String s = n.asText("0").trim();
        if (s.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
