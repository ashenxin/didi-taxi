package com.sx.capacity.model.dto;

import jakarta.validation.constraints.NotNull;

public class DriverOnlineBody {

    /** true：听单中（monitor_status=1）；false：未听单（monitor_status=0） */
    @NotNull(message = "online不能为空")
    private Boolean online;

    public Boolean getOnline() {
        return online;
    }

    public void setOnline(Boolean online) {
        this.online = online;
    }
}
