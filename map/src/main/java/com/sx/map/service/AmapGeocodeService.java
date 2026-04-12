package com.sx.map.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.map.config.AmapProperties;
import com.sx.map.exception.AmapApiException;
import com.sx.map.model.dto.GeocodeDemoResponse;
import com.sx.map.model.dto.RegeoDemoResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 高德地理编码 / 逆地理编码：{@code /v3/geocode/geo}、{@code /v3/geocode/regeo}。
 *
 * <p>文档：<a href="https://lbs.amap.com/api/webservice/guide/api/georegeo">地理/逆地理编码</a>。</p>
 */
@Service
public class AmapGeocodeService {

    private static final String GEO_PATH = "/v3/geocode/geo";
    private static final String REGEO_PATH = "/v3/geocode/regeo";

    private final RestClient amapRestClient;
    private final AmapProperties amapProperties;
    private final ObjectMapper objectMapper;

    public AmapGeocodeService(RestClient amapRestClient, AmapProperties amapProperties, ObjectMapper objectMapper) {
        this.amapRestClient = amapRestClient;
        this.amapProperties = amapProperties;
        this.objectMapper = objectMapper;
    }

    private void ensureKey() {
        if (amapProperties.getKey() == null || amapProperties.getKey().isBlank()) {
            throw new AmapApiException("未配置高德 Key：请设置环境变量 AMAP_KEY / MAP_AMAP_KEY，或 JVM 参数 -DAMAP_KEY / -Dmap.amap.key");
        }
    }

    /**
     * 地理编码：结构化地址 → 经纬度（取首条结果）。
     *
     * @param address 必填，如「北京市朝阳区阜通东大街6号」
     * @param city    可选，限定城市（中文/全拼/citycode/adcode）
     */
    public GeocodeDemoResponse geocode(String address, String city) {
        ensureKey();
        if (!StringUtils.hasText(address)) {
            throw new AmapApiException("address 不能为空");
        }
        UriComponentsBuilder b = UriComponentsBuilder
                .fromUriString(amapProperties.getBaseUrl())
                .path(GEO_PATH)
                .queryParam("key", amapProperties.getKey())
                .queryParam("address", address.trim());
        if (StringUtils.hasText(city)) {
            b.queryParam("city", city.trim());
        }
        // 不可使用 build(true)：true 表示各组件已百分号编码，中文 address/city 未编码时 toUri() 会抛异常
        URI uri = b.encode(StandardCharsets.UTF_8).build().toUri();

        JsonNode root = fetchJson(uri);
        assertStatusOk(root);
        JsonNode list = root.path("geocodes");
        if (!list.isArray() || list.isEmpty()) {
            throw new AmapApiException("高德未返回地理编码结果（geocodes 为空）");
        }
        JsonNode first = list.get(0);
        String loc = text(first, "location");
        double[] lngLat = parseLocation(loc);

        GeocodeDemoResponse resp = new GeocodeDemoResponse();
        resp.setQueryAddress(address.trim());
        resp.setCity(StringUtils.hasText(city) ? city.trim() : null);
        resp.setLng(lngLat[0]);
        resp.setLat(lngLat[1]);
        resp.setProvince(text(first, "province"));
        resp.setCityName(text(first, "city"));
        resp.setDistrict(text(first, "district"));
        resp.setStreet(text(first, "street"));
        resp.setAdcode(text(first, "adcode"));
        resp.setLevel(text(first, "level"));
        resp.setProvider("gaode");
        resp.setTraceId(UUID.randomUUID().toString());
        return resp;
    }

    /**
     * 逆地理编码：经纬度 → 地址（默认 {@code extensions=base}，可通过参数扩展为 {@code all}）。
     *
     * @param lng        经度
     * @param lat        纬度
     * @param radius     可选，周边检索半径（米，0~3000），逆地理基础信息可不传
     * @param extensions {@code base} 或 {@code all}
     */
    public RegeoDemoResponse reverseGeocode(double lng, double lat, Integer radius, String extensions) {
        ensureKey();
        String location = lng + "," + lat;
        String ext = StringUtils.hasText(extensions) ? extensions.trim() : "base";
        if (!"base".equalsIgnoreCase(ext) && !"all".equalsIgnoreCase(ext)) {
            throw new AmapApiException("extensions 仅支持 base 或 all");
        }

        UriComponentsBuilder b = UriComponentsBuilder
                .fromUriString(amapProperties.getBaseUrl())
                .path(REGEO_PATH)
                .queryParam("key", amapProperties.getKey())
                .queryParam("location", location)
                .queryParam("extensions", ext.toLowerCase());
        if (radius != null && radius >= 0 && radius <= 3000) {
            b.queryParam("radius", radius);
        }
        URI uri = b.encode(StandardCharsets.UTF_8).build().toUri();

        JsonNode root = fetchJson(uri);
        assertStatusOk(root);
        JsonNode regeocode = root.path("regeocode");
        if (regeocode.isMissingNode() || regeocode.isNull()) {
            throw new AmapApiException("高德未返回逆地理结果（regeocode 为空）");
        }

        JsonNode ac = regeocode.path("addressComponent");

        RegeoDemoResponse resp = new RegeoDemoResponse();
        resp.setQueryLng(lng);
        resp.setQueryLat(lat);
        resp.setFormattedAddress(text(regeocode, "formatted_address"));
        resp.setCountry(text(ac, "country"));
        resp.setProvince(text(ac, "province"));
        resp.setCity(text(ac, "city"));
        resp.setDistrict(text(ac, "district"));
        resp.setTownship(text(ac, "township"));
        resp.setAdcode(text(ac, "adcode"));
        resp.setProvider("gaode");
        resp.setTraceId(UUID.randomUUID().toString());
        return resp;
    }

    private JsonNode fetchJson(URI uri) {
        String raw = amapRestClient.get().uri(uri).retrieve().body(String.class);
        if (raw == null || raw.isBlank()) {
            throw new AmapApiException("高德返回空响应");
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new AmapApiException("解析高德响应失败: " + e.getMessage());
        }
    }

    private static void assertStatusOk(JsonNode root) {
        String status = root.path("status").asText("");
        if (!"1".equals(status)) {
            throw new AmapApiException("高德请求失败: " + root.path("info").asText("unknown"));
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isMissingNode() || n.isNull()) {
            return "";
        }
        if (n.isArray()) {
            return n.isEmpty() ? "" : n.toString();
        }
        return n.asText("");
    }

    private static double[] parseLocation(String location) {
        if (!StringUtils.hasText(location)) {
            throw new AmapApiException("地理编码结果缺少 location");
        }
        String[] parts = location.split(",");
        if (parts.length < 2) {
            throw new AmapApiException("无法解析 location: " + location);
        }
        try {
            double lng = Double.parseDouble(parts[0].trim());
            double lat = Double.parseDouble(parts[1].trim());
            return new double[]{lng, lat};
        } catch (NumberFormatException e) {
            throw new AmapApiException("无法解析 location: " + location);
        }
    }
}
