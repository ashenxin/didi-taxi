package com.sx.map.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 高德开放平台 Web 服务 Key（驾车路径规划等）。
 * <p>配置项：{@code map.amap.key}；可用环境变量 {@code AMAP_KEY} 或 {@code MAP_AMAP_KEY}，避免提交到仓库。</p>
 */
@ConfigurationProperties(prefix = "map.amap")
public class AmapProperties {

    /**
     * 高德 Web 服务 Key（必填才可调用真实接口）。
     */
    private String key = "";

    /**
     * REST 根地址，默认官方域名。
     */
    private String baseUrl = "https://restapi.amap.com";

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
