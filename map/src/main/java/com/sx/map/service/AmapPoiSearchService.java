package com.sx.map.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.map.config.AmapProperties;
import com.sx.map.exception.AmapApiException;
import com.sx.map.model.dto.AmapPoiCandidate;
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
 * 高德 POI 2.0 文本搜索：{@code /v5/place/text}。
 *
 * 查询结果保留中心点和导航入口、出口，供后续途经点消歧及可达性验证使用。
 */
@Service
@Slf4j
public class AmapPoiSearchService {

    private static final String PLACE_TEXT_PATH = "/v5/place/text";
    private static final int PAGE_SIZE = 10;

    private final RestClient amapRestClient;
    private final AmapProperties amapProperties;
    private final ObjectMapper objectMapper;

    public AmapPoiSearchService(RestClient amapRestClient,
                                AmapProperties amapProperties,
                                ObjectMapper objectMapper) {
        this.amapRestClient = amapRestClient;
        this.amapProperties = amapProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 按名称查询 POI 候选。
     *
     * @param keywords 地点名称，例如“下沙服务区”
     * @param region   可选的城市名称或行政区编码；传入后限制在该区域内搜索
     * @param types    可选的高德 POI 类型编码，多个编码用竖线分隔
     * @return 最多 10 个候选；没有匹配结果时返回空列表
     */
    public List<AmapPoiCandidate> search(String keywords, String region, String types) {
        ensureKey();
        if (!StringUtils.hasText(keywords)) {
            throw new AmapApiException("POI 搜索关键词不能为空");
        }

        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(amapProperties.getBaseUrl())
                .path(PLACE_TEXT_PATH)
                .queryParam("key", amapProperties.getKey())
                .queryParam("keywords", keywords.trim())
                .queryParam("page_size", PAGE_SIZE)
                .queryParam("page_num", 1)
                .queryParam("show_fields", "navi");
        if (StringUtils.hasText(region)) {
            builder.queryParam("region", region.trim())
                    .queryParam("city_limit", true);
        }
        if (StringUtils.hasText(types)) {
            builder.queryParam("types", types.trim());
        }

        URI uri = builder.encode(StandardCharsets.UTF_8).build().toUri();
        JsonNode root = fetchJson(uri);
        assertStatusOk(root);

        JsonNode pois = root.path("pois");
        if (!pois.isArray() || pois.isEmpty()) {
            log.info("高德 POI 搜索无结果 keywords={} region={}", keywords.trim(), normalized(region));
            return List.of();
        }

        List<AmapPoiCandidate> candidates = new ArrayList<>(Math.min(pois.size(), PAGE_SIZE));
        for (JsonNode poi : pois) {
            candidates.add(toCandidate(poi));
        }
        log.info("高德 POI 搜索成功 keywords={} region={} count={}",
                keywords.trim(), normalized(region), candidates.size());
        return List.copyOf(candidates);
    }

    private void ensureKey() {
        if (!StringUtils.hasText(amapProperties.getKey())) {
            throw new AmapApiException("未配置高德 Key：请设置环境变量 AMAP_KEY / MAP_AMAP_KEY，或 JVM 参数 -DAMAP_KEY / -Dmap.amap.key");
        }
    }

    private JsonNode fetchJson(URI uri) {
        String raw = amapRestClient.get().uri(uri).retrieve().body(String.class);
        if (!StringUtils.hasText(raw)) {
            throw new AmapApiException("高德返回空响应");
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            log.error("高德 POI 搜索响应解析失败 path={}", uri.getPath(), e);
            throw new AmapApiException("解析高德 POI 响应失败: " + e.getMessage());
        }
    }

    private static void assertStatusOk(JsonNode root) {
        if (!"1".equals(root.path("status").asText(""))) {
            throw new AmapApiException("高德 POI 搜索失败: " + root.path("info").asText("unknown"));
        }
    }

    private static AmapPoiCandidate toCandidate(JsonNode poi) {
        AmapPoiCandidate candidate = new AmapPoiCandidate();
        candidate.setPoiId(text(poi, "id"));
        candidate.setName(text(poi, "name"));
        candidate.setType(text(poi, "type"));
        candidate.setTypeCode(text(poi, "typecode"));
        candidate.setProvince(text(poi, "pname"));
        candidate.setCity(text(poi, "cityname"));
        candidate.setDistrict(text(poi, "adname"));
        candidate.setAddress(text(poi, "address"));
        candidate.setCenterPoint(parsePoint(text(poi, "location")));

        JsonNode navi = poi.path("navi");
        candidate.setEntrancePoint(parsePoint(text(navi, "entr_location")));
        candidate.setExitPoint(parsePoint(text(navi, "exit_location")));
        return candidate;
    }

    private static Point parsePoint(String location) {
        if (!StringUtils.hasText(location)) {
            return null;
        }
        String[] parts = location.split(",");
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

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.isArray()) {
            return null;
        }
        String text = value.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private static String normalized(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }
}
