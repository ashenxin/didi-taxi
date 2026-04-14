package com.sx.capacity.model.dto;

import jakarta.validation.constraints.NotNull;

public class DriverOnlineBody {

    /** true：听单中（monitor_status=1）；false：未听单（monitor_status=0） */
    @NotNull(message = "online不能为空")
    private Boolean online;

    /**
     * 听单上线时建议上报（GPS 或逆地理），用于写入 Redis 司机池 GEO；缺省则仅更新库表、不参与 GEO 匹配。
     */
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
