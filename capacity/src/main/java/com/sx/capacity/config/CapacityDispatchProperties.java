package com.sx.capacity.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 运力派单相关配置（如自测 GEO 锚点）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "capacity.dispatch")
public class CapacityDispatchProperties {

    /**
     * 指定司机上线时强制写入 Redis GEO 的坐标（与 passenger-demo 东站起点对齐，避免本机浏览器定位超 3km 或非最近邻）。
     * key：driverId；未配置的司机仍使用请求体中的 lat/lng。
     */
    private Map<Long, GeoPin> geoPin = new HashMap<>();

    @Getter
    @Setter
    public static class GeoPin {
        private double lat;
        private double lng;
    }
}
