package com.sx.map.controller;

import com.sx.map.common.util.ResultUtil;
import com.sx.map.common.vo.ResponseVo;
import com.sx.map.model.dto.RouteRequest;
import com.sx.map.model.dto.RouteResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 地图服务：路线与里程/时长预估（MVP 为 stub，后续接第三方地图）。
 * 统一前缀：{@code /api/v1/map}。
 */
@RestController
@RequestMapping("/api/v1/map")
public class RouteController {

    /**
     * 路线规划（当前返回固定 stub 里程/秒数，供计费与下单联调）。
     * {@code POST /api/v1/map/route}
     */
    @PostMapping("/route")
    public ResponseVo<RouteResponse> route(@RequestBody @Valid RouteRequest body) {
        RouteResponse resp = new RouteResponse();
        // MVP stub：先返回一组“可用于计费联调”的假数据；后续接入第三方地图再替换
        resp.setDistanceMeters(12_340L);
        resp.setDurationSeconds(1_560L);
        resp.setProvider("stub");
        resp.setTraceId(String.valueOf(Instant.now().toEpochMilli()));
        return ResultUtil.success(resp);
    }
}

