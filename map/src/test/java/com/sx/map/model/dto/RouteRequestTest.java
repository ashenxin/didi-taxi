package com.sx.map.model.dto;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteRequestTest {

    /**
     * 入口、出口的先后顺序是高速方向判断的一部分，DTO不能重排调用方给出的集合。
     * 同时复制集合结构，避免调用方在请求创建后增删元素，改变实际发送给高德的途经点。
     */
    @Test
    void keepsOrderedWaypointsAndCopiesCollectionStructure() {
        Point entrance = point(120.10, 30.10);
        Point exit = point(120.11, 30.10);
        List<Point> source = new ArrayList<>(List.of(entrance, exit));
        RouteRequest request = new RouteRequest();

        request.setWaypoints(source);
        source.clear();

        assertThat(request.getWaypoints()).containsExactly(entrance, exit);
        assertThatThrownBy(() -> request.getWaypoints().add(point(120.12, 30.10)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * 旧调用方仍可使用单途经点访问器；兼容方法必须映射到新的有序集合，而不是保存第二份状态。
     */
    @SuppressWarnings("deprecation")
    @Test
    void mapsLegacySingleWaypointAccessorsToOrderedWaypoints() {
        Point waypoint = point(120.15, 30.15);
        RouteRequest request = new RouteRequest();

        request.setWaypoint(waypoint);

        assertThat(request.getWaypoint()).isSameAs(waypoint);
        assertThat(request.getWaypoints()).containsExactly(waypoint);

        request.setWaypoint(null);
        assertThat(request.getWaypoint()).isNull();
        assertThat(request.getWaypoints()).isEmpty();
    }

    private static Point point(double lng, double lat) {
        Point point = new Point();
        point.setLng(lng);
        point.setLat(lat);
        return point;
    }
}
