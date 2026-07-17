package com.sx.passengerapi.model.order;

import jakarta.validation.constraints.NotBlank;

public class Place {

    /**
     * 纬度；必须与 {@link #lng} 同时由前端选点传入。
     */
    private Double lat;

    /**
     * 经度；必须与纬度同时传入。
     */
    private Double lng;

    @NotBlank(message = "请填写地点名称")
    private String name;

    /**
     * 结构化地址，用于订单展示。
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
