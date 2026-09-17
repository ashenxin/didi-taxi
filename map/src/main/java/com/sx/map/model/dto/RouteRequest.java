package com.sx.map.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RouteRequest {

    @NotNull(message = "origin不能为空")
    @Valid
    private Point origin;

    @NotNull(message = "dest不能为空")
    @Valid
    private Point dest;

    /**
     * 按车辆实际经过顺序排列的途经点。
     *
     * 服务区和高速加油站需要依次传入入口、出口，不能只传POI中心点。顺序由业务层确定，
     * 地图适配器必须原样发送给高德，不能重新排序。高德驾车接口最多支持16个途经点，
     * 这里提前执行相同限制，避免发出必然失败的外部请求。
     */
    @Valid
    @Size(max = 16, message = "waypoints最多允许16个途经点")
    private List<@NotNull(message = "waypoints不能包含空坐标") @Valid Point> waypoints = List.of();

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

    public List<Point> getWaypoints() {
        return Collections.unmodifiableList(waypoints);
    }

    public void setWaypoints(List<Point> waypoints) {
        this.waypoints = waypoints == null ? List.of() : new ArrayList<>(waypoints);
    }

    /**
     * 兼容既有单途经点调用。新路线规划代码应使用 {@link #getWaypoints()}，明确表达有序途经点。
     */
    @Deprecated(forRemoval = false)
    public Point getWaypoint() {
        return waypoints.isEmpty() ? null : waypoints.getFirst();
    }

    /**
     * 兼容既有单途经点调用。传入非空值时转换为只包含一个元素的有序集合。
     */
    @Deprecated(forRemoval = false)
    public void setWaypoint(Point waypoint) {
        this.waypoints = waypoint == null ? List.of() : new ArrayList<>(List.of(waypoint));
    }
}
