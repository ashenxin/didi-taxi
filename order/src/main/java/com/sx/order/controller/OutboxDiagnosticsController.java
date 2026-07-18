package com.sx.order.controller;

import com.sx.order.common.util.ResultUtil;
import com.sx.order.common.vo.ResponseVo;
import com.sx.order.model.OrderOutboxEvent;
import com.sx.order.service.OutboxDiagnosticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 订单 Outbox 与派单链路诊断接口，用于查看事件发布状态、失败记录和跨服务派单轨迹。
 * 统一前缀：{@code /api/v1/orders/internal}；仅供内部运维与联调使用。
 */
@RestController
@RequestMapping("/api/v1/orders/internal")
public class OutboxDiagnosticsController {

    private final OutboxDiagnosticsService service;

    public OutboxDiagnosticsController(OutboxDiagnosticsService service) {
        this.service = service;
    }

    /**
     * 汇总 Outbox 各状态的事件数量和积压情况。
     * {@code GET /api/v1/orders/internal/outbox/summary}
     */
    @GetMapping("/outbox/summary")
    public ResponseVo<Map<String, Object>> summary() {
        return ResultUtil.success(service.summary());
    }

    /**
     * 按订单号查询全部 Outbox 事件。
     * {@code GET /api/v1/orders/internal/outbox/by-order/{orderNo}}
     */
    @GetMapping("/outbox/by-order/{orderNo}")
    public ResponseVo<List<OrderOutboxEvent>> byOrder(@PathVariable String orderNo) {
        return ResultUtil.success(service.byOrder(orderNo));
    }

    /**
     * 查询最近发布失败的 Outbox 事件，默认返回 50 条。
     * {@code GET /api/v1/orders/internal/outbox/failed?limit=}
     */
    @GetMapping("/outbox/failed")
    public ResponseVo<List<OrderOutboxEvent>> failed(@RequestParam(required = false, defaultValue = "50") Integer limit) {
        return ResultUtil.success(service.failed(limit == null ? 50 : limit));
    }

    /**
     * 将指定失败事件重新置为可发布状态，由 Outbox 发布任务再次投递。
     * {@code POST /api/v1/orders/internal/outbox/{id}/retry}
     */
    @PostMapping("/outbox/{id}/retry")
    public ResponseVo<OrderOutboxEvent> retry(@PathVariable Long id) {
        return ResultUtil.success(service.retry(id));
    }

    /**
     * 聚合指定订单的 Outbox、派单请求和事件消费信息，形成派单诊断轨迹。
     * {@code GET /api/v1/orders/internal/dispatch-trace/{orderNo}}
     */
    @GetMapping("/dispatch-trace/{orderNo}")
    public ResponseVo<Map<String, Object>> dispatchTrace(@PathVariable String orderNo) {
        return ResultUtil.success(service.dispatchTrace(orderNo));
    }
}
