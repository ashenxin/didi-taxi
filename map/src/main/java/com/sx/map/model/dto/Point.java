package com.sx.map.model.dto;

import jakarta.validation.constraints.NotNull;

public class Point {

    @NotNull(message = "lat不能为空")
    private Double lat;

    @NotNull(message = "lng不能为空")
    private Double lng;

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
}

