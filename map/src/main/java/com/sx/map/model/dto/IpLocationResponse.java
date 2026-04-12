package com.sx.map.model.dto;

/**
 * IP 定位结果（高德 {@code /v3/ip} 解析后的常用字段）。
 */
public class IpLocationResponse {

    /**
     * 请求中传入的 IP；未传时本服务调用高德不带 ip 参数，高德将按「对高德的 HTTP 请求来源」解析（一般为当前服务出口 IP）。
     */
    private String queryIp;

    private String province;

    private String city;

    /**
     * 城市 adcode。
     */
    private String adcode;

    /**
     * 城市矩形范围（高德返回的原始字符串，多为「左下;右上」坐标对）。
     */
    private String rectangle;

    private String provider;

    private String traceId;

    public String getQueryIp() {
        return queryIp;
    }

    public void setQueryIp(String queryIp) {
        this.queryIp = queryIp;
    }

    public String getProvince() {
        return province;
    }

    public void setProvince(String province) {
        this.province = province;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getAdcode() {
        return adcode;
    }

    public void setAdcode(String adcode) {
        this.adcode = adcode;
    }

    public String getRectangle() {
        return rectangle;
    }

    public void setRectangle(String rectangle) {
        this.rectangle = rectangle;
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
