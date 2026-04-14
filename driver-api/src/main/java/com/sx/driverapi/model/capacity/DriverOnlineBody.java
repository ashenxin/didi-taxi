package com.sx.driverapi.model.capacity;

import jakarta.validation.constraints.NotNull;

public class DriverOnlineBody {

    @NotNull(message = "online不能为空")
    private Boolean online;

    /** 听单上线时上报坐标，写入 Redis 司机池；可选 */
    private Double lat;
    private Double lng;

    public Boolean getOnline() {
        return online;
    }

    public void setOnline(Boolean online) {
        this.online = online;
    }

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
