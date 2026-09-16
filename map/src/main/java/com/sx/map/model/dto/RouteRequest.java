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

    /**
     * 可选的单个途经点；为空时保持普通起终点算路。
     */
    @Valid
    private Point waypoint;

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

    public Point getWaypoint() {
        return waypoint;
    }

    public void setWaypoint(Point waypoint) {
        this.waypoint = waypoint;
    }
}
