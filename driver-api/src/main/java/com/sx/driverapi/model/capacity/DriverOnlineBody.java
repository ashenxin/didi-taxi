package com.sx.driverapi.model.capacity;

import jakarta.validation.constraints.NotNull;

public class DriverOnlineBody {

    @NotNull(message = "online不能为空")
    private Boolean online;

    public Boolean getOnline() {
        return online;
    }

    public void setOnline(Boolean online) {
        this.online = online;
    }
}
