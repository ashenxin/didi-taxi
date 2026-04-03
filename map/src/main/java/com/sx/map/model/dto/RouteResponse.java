package com.sx.map.model.dto;

/**
 * 路线规划结果（MVP 版：先返回里程/时长，后续可扩展 polyline、途径点、费用等）。
 */
public class RouteResponse {
    /**
     * 预估路线里程（单位：米）。
     *
     * <p>来源：第三方地图 route 接口的 distance，或你们自研路网计算结果。</p>
     */
    private Long distanceMeters;

    /**
     * 预估行驶时长（单位：秒）。
     *
     * <p>来源：第三方地图 route 接口的 duration，或你们自研 ETA 结果。</p>
     */
    private Long durationSeconds;

    /**
     * 数据提供方标识。
     *
     * <p>例如：{@code gaode}/{@code baidu}/{@code stub}。</p>
     */
    private String provider;

    /**
     * 本次地图计算的追踪 ID（用于排障/定位第三方请求）。
     *
     * <p>MVP 先用时间戳/UUID；后续接入第三方时可写入其 requestId 或你方生成的 requestId。</p>
     */
    private String traceId;

    public Long getDistanceMeters() {
        return distanceMeters;
    }

    public void setDistanceMeters(Long distanceMeters) {
        this.distanceMeters = distanceMeters;
    }

    public Long getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Long durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}

