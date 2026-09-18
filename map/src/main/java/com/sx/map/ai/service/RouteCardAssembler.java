package com.sx.map.ai.service;

import com.sx.map.ai.dto.RouteCardDto;
import com.sx.map.ai.model.PassengerRouteSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把 map-service 内部的完整路线快照组装为客服卡片。
 * 卡片只携带展示所需字段；完整折线用于有效期内展示，导航步骤留在服务端。
 */
@Component
public class RouteCardAssembler {

    public RouteCardDto fromSnapshot(PassengerRouteSnapshot snapshot) {
        List<RouteCardDto.GeoPointView> polyline = snapshot.polyline().stream()
                .map(point -> new RouteCardDto.GeoPointView(point.longitude(), point.latitude()))
                .toList();
        return new RouteCardDto(
                snapshot.routeRef(),
                new RouteCardDto.PlaceView(snapshot.origin().name()),
                new RouteCardDto.PlaceView(snapshot.destination().name()),
                null,
                new RouteCardDto.RouteView(snapshot.coordinateSystem(),
                        snapshot.distanceMeters(), snapshot.durationSeconds(), polyline),
                snapshot.generatedAt(),
                snapshot.expiresAt());
    }
}
