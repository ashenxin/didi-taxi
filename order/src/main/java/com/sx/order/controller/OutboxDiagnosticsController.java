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

@RestController
@RequestMapping("/api/v1/orders/internal")
public class OutboxDiagnosticsController {

    private final OutboxDiagnosticsService service;

    public OutboxDiagnosticsController(OutboxDiagnosticsService service) {
        this.service = service;
    }

    @GetMapping("/outbox/summary")
    public ResponseVo<Map<String, Object>> summary() {
        return ResultUtil.success(service.summary());
    }

    @GetMapping("/outbox/by-order/{orderNo}")
    public ResponseVo<List<OrderOutboxEvent>> byOrder(@PathVariable String orderNo) {
        return ResultUtil.success(service.byOrder(orderNo));
    }

    @GetMapping("/outbox/failed")
    public ResponseVo<List<OrderOutboxEvent>> failed(@RequestParam(required = false, defaultValue = "50") Integer limit) {
        return ResultUtil.success(service.failed(limit == null ? 50 : limit));
    }

    @PostMapping("/outbox/{id}/retry")
    public ResponseVo<OrderOutboxEvent> retry(@PathVariable Long id) {
        return ResultUtil.success(service.retry(id));
    }

    @GetMapping("/dispatch-trace/{orderNo}")
    public ResponseVo<Map<String, Object>> dispatchTrace(@PathVariable String orderNo) {
        return ResultUtil.success(service.dispatchTrace(orderNo));
    }
}
