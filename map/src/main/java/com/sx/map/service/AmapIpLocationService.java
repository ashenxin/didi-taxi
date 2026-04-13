package com.sx.map.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.map.config.AmapProperties;
import com.sx.map.exception.AmapApiException;
import com.sx.map.model.dto.IpLocationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * 高德「IP 定位」Web 服务：{@code /v3/ip}。
 *
 * 文档：<a href="https://lbs.amap.com/api/webservice/guide/api/ipconfig">IP 定位</a>。
 */
@Service
@Slf4j
public class AmapIpLocationService {

    private static final String IP_PATH = "/v3/ip";

    private final RestClient amapRestClient;
    private final AmapProperties amapProperties;
    private final ObjectMapper objectMapper;

    public AmapIpLocationService(RestClient amapRestClient, AmapProperties amapProperties, ObjectMapper objectMapper) {
        this.amapRestClient = amapRestClient;
        this.amapProperties = amapProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * @param ip 可选，国内 IPv4。为空则请求不带 ip 参数，高德按「调用本接口的客户端 IP」解析（服务端直连时为出口 IP）。
     */
    public IpLocationResponse locate(String ip) {
        if (amapProperties.getKey() == null || amapProperties.getKey().isBlank()) {
            throw new AmapApiException("未配置高德 Key：请设置环境变量 AMAP_KEY / MAP_AMAP_KEY，或 JVM 参数 -DAMAP_KEY / -Dmap.amap.key");
        }
        String trimmed = StringUtils.hasText(ip) ? ip.trim() : null;

        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(amapProperties.getBaseUrl())
                .path(IP_PATH)
                .queryParam("key", amapProperties.getKey());
        if (trimmed != null) {
            builder.queryParam("ip", trimmed);
        }
        URI uri = builder.build(true).toUri();

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
                throw new AmapApiException("高德 IP 定位失败: " + info);
            }

            IpLocationResponse resp = new IpLocationResponse();
            resp.setQueryIp(trimmed);
            resp.setProvince(textOrEmpty(root, "province"));
            resp.setCity(textOrEmpty(root, "city"));
            resp.setAdcode(textOrEmpty(root, "adcode"));
            resp.setRectangle(textOrEmpty(root, "rectangle"));
            resp.setProvider("gaode");
            resp.setTraceId(UUID.randomUUID().toString());
            log.info("amap ip locate ok ip={} adcode={}", trimmed, resp.getAdcode());
            return resp;
        } catch (AmapApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("amap ip locate parse failed", e);
            throw new AmapApiException("解析高德 IP 响应失败: " + e.getMessage());
        }
    }

    private static String textOrEmpty(JsonNode root, String field) {
        JsonNode n = root.path(field);
        if (n.isMissingNode() || n.isNull()) {
            return "";
        }
        return n.asText("");
    }
}
