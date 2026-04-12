package com.sx.passengerapi.model.order;

import jakarta.validation.constraints.NotBlank;

public class Place {

    /**
     * 纬度；可与 {@link #lng} 同时由前端传入（地图选点）。若缺省则由 BFF 调地理编码根据 {@link #address}/{@link #name} 补全。
     */
    private Double lat;

    /**
     * 经度；缺省时由 BFF 地理编码补全。
     */
    private Double lng;

    @NotBlank(message = "请填写地点名称")
    private String name;

    /**
     * 结构化地址或可与 name 一起参与地理编码；优先于 name 作为 geocode 的 address 参数。
     */
    private String address;

    public Double getLat() {
        return lat;
    }

    public void setLat(Double lat) {
        this.lat = lat;
    }

    public Double getLng() {
        return lng;
    }

    public void setLng(Double lng) {
        this.lng = lng;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }
}

