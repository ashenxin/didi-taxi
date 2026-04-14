package com.sx.capacity.controller;

import com.sx.capacity.common.util.ResultUtil;
import com.sx.capacity.common.vo.ResponseVo;
import com.sx.capacity.model.dto.NearestDriverResult;
import com.sx.capacity.model.dto.PendingOrderIndexBody;
import com.sx.capacity.service.DispatchOrderPoolService;
import com.sx.capacity.service.NearestDriverQueryService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 派单相关接口：Redis GEO 司机池 + DB 校验；无坐标时回退 DB 首条候选。
 * 统一前缀：{@code /api/v1/dispatch}；通常由 {@code passenger-api} 下单链路调用。
 */
@RestController
@RequestMapping("/api/v1/dispatch")
@Slf4j
public class DispatchController {

    private final NearestDriverQueryService nearestDriverQueryService;
    private final DispatchOrderPoolService dispatchOrderPoolService;

    public DispatchController(NearestDriverQueryService nearestDriverQueryService,
                              DispatchOrderPoolService dispatchOrderPoolService) {
        this.nearestDriverQueryService = nearestDriverQueryService;
        this.dispatchOrderPoolService = dispatchOrderPoolService;
    }

    /**
     * 查询「可派单」司机：若提供上车点经纬度则按 Redis GEO 最近 + 业务过滤；否则回退 MVP 逻辑。
     * {@code GET /api/v1/dispatch/nearest-driver?cityCode=&productCode=&originLat=&originLng=}
     */
    @GetMapping("/nearest-driver")
    public ResponseVo<NearestDriverResult> searchNearestDriver(@RequestParam String cityCode,
                                                              @RequestParam(required = false) String productCode,
                                                              @RequestParam(required = false) Double originLat,
                                                              @RequestParam(required = false) Double originLng) {
        if (cityCode == null || cityCode.isBlank()) {
            return ResultUtil.requestError("cityCode不能为空");
        }

        NearestDriverResult hit = nearestDriverQueryService.findNearest(cityCode, productCode, originLat, originLng);
        if (hit == null) {
            return ResultUtil.error(404, "无可接单在线司机");
        }
        return ResultUtil.success(hit);
    }

    /**
     * 指派成功后写入订单池索引（派生缓存，可选；司机待接单列表仍以订单库为准）。
     * {@code POST /api/v1/dispatch/pending-order-index}
     */
    @PostMapping("/pending-order-index")
    public ResponseVo<Void> addPendingOrderIndex(@RequestBody @Valid PendingOrderIndexBody body) {
        dispatchOrderPoolService.addPending(body.getDriverId(), body.getOrderNo());
        return ResultUtil.success(null);
    }
}
