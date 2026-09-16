package com.sx.map.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.map.config.AmapProperties;
import com.sx.map.exception.AmapApiException;
import com.sx.map.model.dto.Point;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 将 GPS/WGS84 坐标批量转换为高德坐标。
 */
@Service
@Slf4j
public class AmapCoordinateConvertService {

    private static final String CONVERT_PATH = "/v3/assistant/coordinate/convert";
    private static final int MAX_POINTS = 40;

    private final RestClient amapRestClient;
    private final AmapProperties amapProperties;
    private final ObjectMapper objectMapper;

    public AmapCoordinateConvertService(RestClient amapRestClient,
                                        AmapProperties amapProperties,
                                        ObjectMapper objectMapper) {
        this.amapRestClient = amapRestClient;
        this.amapProperties = amapProperties;
        this.objectMapper = objectMapper;
    }

    public List<Point> convertWgs84ToAmap(List<Point> points) {
        if (!StringUtils.hasText(amapProperties.getKey())) {
            throw new AmapApiException("未配置高德 Key：请设置环境变量 AMAP_KEY / MAP_AMAP_KEY，或 JVM 参数 -DAMAP_KEY / -Dmap.amap.key");
        }
        if (points == null || points.isEmpty() || points.size() > MAX_POINTS) {
            throw new AmapApiException("坐标转换点数量必须为 1~40 个");
        }

        String locations = points.stream()
                .map(AmapCoordinateConvertService::toAmapCoord)
                .reduce((left, right) -> left + "|" + right)
                .orElseThrow();
        URI uri = UriComponentsBuilder
                .fromUriString(amapProperties.getBaseUrl())
                .path(CONVERT_PATH)
                .queryParam("key", amapProperties.getKey())
                .queryParam("locations", locations)
                .queryParam("coordsys", "gps")
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUri();

        JsonNode root = fetchJson(uri);
        if (!"1".equals(root.path("status").asText(""))) {
            throw new AmapApiException("高德坐标转换失败: " + root.path("info").asText("unknown"));
        }
        List<Point> converted = parseLocations(root.path("locations").asText(""));
        if (converted.size() != points.size()) {
            throw new AmapApiException("高德坐标转换结果数量与请求不一致");
        }
        return converted;
    }

    private JsonNode fetchJson(URI uri) {
        String raw = amapRestClient.get().uri(uri).retrieve().body(String.class);
        if (!StringUtils.hasText(raw)) {
            throw new AmapApiException("高德返回空响应");
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            log.error("高德坐标转换响应解析失败 path={}", uri.getPath(), e);
            throw new AmapApiException("解析高德坐标转换响应失败: " + e.getMessage());
        }
    }

    private static List<Point> parseLocations(String locations) {
        if (!StringUtils.hasText(locations)) {
            return List.of();
        }
        List<Point> points = new ArrayList<>();
        for (String coordinate : locations.split(";")) {
            String[] parts = coordinate.split(",");
            if (parts.length != 2) {
                throw new AmapApiException("高德返回无法解析的转换坐标");
            }
            try {
                Point point = new Point();
                point.setLng(Double.parseDouble(parts[0].trim()));
                point.setLat(Double.parseDouble(parts[1].trim()));
                points.add(point);
            } catch (NumberFormatException e) {
                throw new AmapApiException("高德返回无法解析的转换坐标");
            }
        }
        return List.copyOf(points);
    }

    private static String toAmapCoord(Point point) {
        if (point == null || point.getLng() == null || point.getLat() == null) {
            throw new AmapApiException("待转换坐标缺少有效经纬度");
        }
        return point.getLng() + "," + point.getLat();
    }
}
