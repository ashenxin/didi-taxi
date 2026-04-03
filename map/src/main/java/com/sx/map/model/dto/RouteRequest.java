package com.sx.map.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public class RouteRequest {

    @NotNull(message = "origin不能为空")
    @Valid
    private Point origin;

    @NotNull(message = "dest不能为空")
    @Valid
    private Point dest;

    public Point getOrigin() {
        return origin;
    }

    public void setOrigin(Point origin) {
        this.origin = origin;
    }

    public Point getDest() {
        return dest;
    }

    public void setDest(Point dest) {
        this.dest = dest;
    }
}

