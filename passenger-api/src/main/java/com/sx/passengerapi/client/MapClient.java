package com.sx.passengerapi.client;

import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.map.GeocodeDemoResponse;
import com.sx.passengerapi.model.map.RouteRequest;
import com.sx.passengerapi.model.map.RouteResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "map", url = "${services.map.base-url:http://127.0.0.1:8094}")
public interface MapClient {

    /**
     * 高德地理编码：结构化地址/中文地名 → 经纬度（map-service demo）。
     */
    @GetMapping("/api/v1/map/demo/amap-geocode")
    ResponseVo<GeocodeDemoResponse> geocode(
            @RequestParam("address") String address,
            @RequestParam(value = "city", required = false) String city);

    /**
     * 高德驾车路径规划：起终点经纬度 → 里程/时长（map-service demo）。
     */
    @PostMapping("/api/v1/map/demo/amap-driving")
    ResponseVo<RouteResponse> drivingRoute(@RequestBody RouteRequest body);
}

