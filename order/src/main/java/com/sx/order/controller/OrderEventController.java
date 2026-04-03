package com.sx.order.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.order.common.util.ResultUtil;
import com.sx.order.common.vo.ResponseVo;
import com.sx.order.dao.OrderEventEntityMapper;
import com.sx.order.model.OrderEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单事件流水查询（与 {@link TripOrderController} 同前缀，由 Spring 合并映射）。
 * <p>统一前缀：{@code /api/v1/orders}。</p>
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderEventController {

    private final OrderEventEntityMapper orderEventEntityMapper;

    public OrderEventController(OrderEventEntityMapper orderEventEntityMapper) {
        this.orderEventEntityMapper = orderEventEntityMapper;
    }

    /**
     * 按订单号查询事件时间线（按发生时间升序）。
     * <p>{@code GET /api/v1/orders/{orderNo}/events}</p>
     */
    @GetMapping("/{orderNo}/events")
    public ResponseVo<List<OrderEvent>> listByOrderNo(@PathVariable String orderNo) {
        List<OrderEvent> rows = orderEventEntityMapper.selectList(
                Wrappers.<OrderEvent>lambdaQuery()
                        .eq(OrderEvent::getOrderNo, orderNo)
                        .orderByAsc(OrderEvent::getOccurredAt, OrderEvent::getId));
        return ResultUtil.success(rows);
    }
}
