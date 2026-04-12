package com.sx.map.controller;

import com.sx.map.common.util.ResultUtil;
import com.sx.map.common.vo.ResponseVo;
import com.sx.map.model.dto.GeocodeDemoResponse;
import com.sx.map.model.dto.IpLocationResponse;
import com.sx.map.model.dto.RegeoDemoResponse;
import com.sx.map.model.dto.RouteRequest;
import com.sx.map.model.dto.RouteResponse;
import com.sx.map.service.AmapDrivingRouteService;
import com.sx.map.service.AmapGeocodeService;
import com.sx.map.service.AmapIpLocationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 高德 Web 服务 Demo：驾车路径规划、IP 定位、地理编码、逆地理编码。
 * <p>驾车结果与 {@link RouteController} 的 {@link RouteResponse} 对齐，便于替换 stub。</p>
 * <p>路径：{@code amap-driving}、{@code amap-ip}、{@code amap-geocode}、{@code amap-regeo}（均位于 {@code /api/v1/map/demo} 下）。</p>
 */
@RestController
@RequestMapping("/api/v1/map/demo")
public class AmapDrivingDemoController {

    private final AmapDrivingRouteService amapDrivingRouteService;
    private final AmapIpLocationService amapIpLocationService;
    private final AmapGeocodeService amapGeocodeService;

    public AmapDrivingDemoController(
            AmapDrivingRouteService amapDrivingRouteService,
            AmapIpLocationService amapIpLocationService,
            AmapGeocodeService amapGeocodeService) {
        this.amapDrivingRouteService = amapDrivingRouteService;
        this.amapIpLocationService = amapIpLocationService;
        this.amapGeocodeService = amapGeocodeService;
    }

    /**
     * 驾车路径规划（高德），返回里程与时长。
     * <p>{@code POST /api/v1/map/demo/amap-driving}</p>
     */
    @PostMapping("/amap-driving")
    public ResponseVo<RouteResponse> amapDriving(@RequestBody @Valid RouteRequest body) {
        return ResultUtil.success(amapDrivingRouteService.drivingRoute(body));
    }

    /**
     * IP 定位（高德）。传 {@code ip} 时解析该地址；不传则高德按「当前对高德的 HTTP 请求来源 IP」解析（本服务直连时一般为服务器出口 IP）。
     */
    @GetMapping("/amap-ip")
    public ResponseVo<IpLocationResponse> amapIp(@RequestParam(value = "ip", required = false) String ip) {
        return ResultUtil.success(amapIpLocationService.locate(ip));
    }

    /**
     * 地理编码：结构化地址 → 经纬度（取高德首条结果）。
     * <p>{@code GET /api/v1/map/demo/amap-geocode?address=&city=}</p>
     */
    @GetMapping("/amap-geocode")
    public ResponseVo<GeocodeDemoResponse> amapGeocode(
            @RequestParam String address,
            @RequestParam(required = false) String city) {
        return ResultUtil.success(amapGeocodeService.geocode(address, city));
    }

    /**
     * 逆地理编码：经纬度 → 地址（{@code extensions} 默认 {@code base}，可传 {@code all} 以返回周边 POI 等，响应体仍为摘要字段）。
     * <p>{@code GET /api/v1/map/demo/amap-regeo?lng=&lat=&radius=&extensions=}</p>
     */
    @GetMapping("/amap-regeo")
    public ResponseVo<RegeoDemoResponse> amapRegeo(
            @RequestParam double lng,
            @RequestParam double lat,
            @RequestParam(required = false) Integer radius,
            @RequestParam(required = false) String extensions) {
        return ResultUtil.success(amapGeocodeService.reverseGeocode(lng, lat, radius, extensions));
    }
}
