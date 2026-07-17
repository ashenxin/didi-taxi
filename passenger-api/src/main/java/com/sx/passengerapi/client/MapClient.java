package com.sx.passengerapi.client;

import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.map.RouteRequest;
import com.sx.passengerapi.model.map.RouteResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "map", url = "${services.map.base-url:http://127.0.0.1:8094}")
public interface MapClient {

    /**
     * 本地 mock 路线规划：起终点经纬度 → 稳定的里程/时长。
     */
    @PostMapping("/api/v1/map/route")
    ResponseVo<RouteResponse> drivingRoute(@RequestBody RouteRequest body);
}
