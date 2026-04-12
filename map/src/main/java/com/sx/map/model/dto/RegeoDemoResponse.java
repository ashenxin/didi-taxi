package com.sx.map.model.dto;

/**
 * 逆地理编码（坐标 → 地址）Demo 返回，取自高德 {@code /v3/geocode/regeo} 的 {@code regeocode}。
 */
public class RegeoDemoResponse {

    private Double queryLng;
    private Double queryLat;

    /** 结构化地址描述。 */
    private String formattedAddress;

    private String country;
    private String province;
    private String city;
    private String district;
    private String township;
    private String adcode;

    private String provider;
    private String traceId;

    public Double getQueryLng() {
        return queryLng;
    }

    public void setQueryLng(Double queryLng) {
        this.queryLng = queryLng;
    }

    public Double getQueryLat() {
        return queryLat;
    }

    public void setQueryLat(Double queryLat) {
        this.queryLat = queryLat;
    }

    public String getFormattedAddress() {
        return formattedAddress;
    }

    public void setFormattedAddress(String formattedAddress) {
        this.formattedAddress = formattedAddress;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
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

    public String getDistrict() {
        return district;
    }

    public void setDistrict(String district) {
        this.district = district;
    }

    public String getTownship() {
        return township;
    }

    public void setTownship(String township) {
        this.township = township;
    }

    public String getAdcode() {
        return adcode;
    }

    public void setAdcode(String adcode) {
        this.adcode = adcode;
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
