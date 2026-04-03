package com.sx.driverapi.controller;

import com.sx.driverapi.common.util.ResultUtil;
import com.sx.driverapi.common.vo.ResponseVo;
import com.sx.driverapi.model.capacity.DriverOnlineBody;
import com.sx.driverapi.model.order.AssignedOrderItemVO;
import com.sx.driverapi.model.order.DriverIdBody;
import com.sx.driverapi.model.order.FinishOrderBody;
import com.sx.driverapi.service.DriverBffService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 司机端聚合接口（BFF），供司机 H5 / App 调用。
 * <p>统一前缀：{@code /driver/api/v1}。对内通过 Feign 调用 {@code capacity}（上线、接单资格）、{@code order}（指派单、状态推进）。</p>
 * <p>统一响应：{@link ResponseVo}，业务码见 {@code code} 字段（200 成功；400/403/404/409/502 等见全局异常与下游透传）。</p>
 */
@RestController
@RequestMapping("/driver/api/v1")
public class DriverBffController {

    private final DriverBffService driverBffService;

    public DriverBffController(DriverBffService driverBffService) {
        this.driverBffService = driverBffService;
    }

    /**
     * 司机上线/下线（听单开关）。
     * <p>{@code POST /driver/api/v1/drivers/{driverId}/online}</p>
     * <p>请求体：{@code { "online": true|false }}，透传运力服务更新 {@code monitor_status}。</p>
     */
    @PostMapping("/drivers/{driverId}/online")
    public ResponseVo<Void> online(@PathVariable Long driverId, @RequestBody @Valid DriverOnlineBody body) {
        driverBffService.setOnline(driverId, Boolean.TRUE.equals(body.getOnline()));
        return ResultUtil.success();
    }

    /**
     * 拉取「已指派给当前司机、待确认接单」的订单列表（派单模式轮询）。
     * <p>{@code GET /driver/api/v1/orders/assigned?driverId=}</p>
     */
    @GetMapping("/orders/assigned")
    public ResponseVo<List<AssignedOrderItemVO>> assigned(@RequestParam Long driverId) {
        return ResultUtil.success(driverBffService.listAssigned(driverId));
    }

    /**
     * 接单（确认指派）：先校验运力侧「可接单且已上线」，再调用订单 {@code ASSIGNED → ACCEPTED}。
     * <p>{@code POST /driver/api/v1/orders/{orderNo}/accept}</p>
     * <p>请求体：{@code { "driverId": 80001 }}，须与订单指派司机一致。</p>
     */
    @PostMapping("/orders/{orderNo}/accept")
    public ResponseVo<Void> accept(@PathVariable String orderNo, @RequestBody @Valid DriverIdBody body) {
        driverBffService.accept(orderNo, body.getDriverId());
        return ResultUtil.success();
    }

    /**
     * 到达上车点：{@code ACCEPTED → ARRIVED}。
     * <p>{@code POST /driver/api/v1/orders/{orderNo}/arrive}</p>
     */
    @PostMapping("/orders/{orderNo}/arrive")
    public ResponseVo<Void> arrive(@PathVariable String orderNo, @RequestBody @Valid DriverIdBody body) {
        driverBffService.arrive(orderNo, body.getDriverId());
        return ResultUtil.success();
    }

    /**
     * 开始行程：{@code ARRIVED → STARTED}。
     * <p>{@code POST /driver/api/v1/orders/{orderNo}/start}</p>
     */
    @PostMapping("/orders/{orderNo}/start")
    public ResponseVo<Void> start(@PathVariable String orderNo, @RequestBody @Valid DriverIdBody body) {
        driverBffService.start(orderNo, body.getDriverId());
        return ResultUtil.success();
    }

    /**
     * 完单：{@code STARTED → FINISHED}；可选上报里程/时长/实付金额（未传实付时由订单服务按预估兜底）。
     * <p>{@code POST /driver/api/v1/orders/{orderNo}/finish}</p>
     */
    @PostMapping("/orders/{orderNo}/finish")
    public ResponseVo<Void> finish(@PathVariable String orderNo, @RequestBody @Valid FinishOrderBody body) {
        driverBffService.finish(orderNo, body);
        return ResultUtil.success();
    }
}
