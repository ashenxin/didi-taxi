package com.sx.adminapi.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdminOrderServiceDisplayTextTest {

    @Test
    void mapsAllCurrentOrderStatuses() {
        assertEquals("-", AdminOrderService.statusText(null));
        assertEquals("待司机确认", AdminOrderService.statusText(7));
    }

    @Test
    void mapsCurrentOrderEventNames() {
        assertEquals("创建订单", AdminOrderService.eventTypeText("ORDER_CREATED"));
        assertEquals("等待派单", AdminOrderService.eventTypeText("ORDER_CREATED_NEED_DISPATCH"));
        assertEquals("派单", AdminOrderService.eventTypeText("ORDER_ASSIGNED"));
        assertEquals("重新派单", AdminOrderService.eventTypeText("ORDER_REASSIGNED"));
        assertEquals("等待司机确认", AdminOrderService.eventTypeText("ORDER_OFFER_OPENED"));
        assertEquals("司机确认超时", AdminOrderService.eventTypeText("ORDER_OFFER_TIMED_OUT"));
        assertEquals("司机接单", AdminOrderService.eventTypeText("ORDER_ACCEPTED"));
        assertEquals("司机拒单", AdminOrderService.eventTypeText("ORDER_DRIVER_REJECTED"));
        assertEquals("司机到达前取消", AdminOrderService.eventTypeText("ORDER_DRIVER_CANCELLED_BEFORE_ARRIVE"));
        assertEquals("司机到达", AdminOrderService.eventTypeText("ORDER_DRIVER_ARRIVED"));
        assertEquals("开始行程", AdminOrderService.eventTypeText("ORDER_TRIP_STARTED"));
        assertEquals("结束行程", AdminOrderService.eventTypeText("ORDER_FINISHED"));
        assertEquals("取消订单", AdminOrderService.eventTypeText("ORDER_CANCELLED"));
    }

    @Test
    void keepsUnknownEventNameVisibleForDiagnostics() {
        assertEquals("ORDER_NEW_EVENT", AdminOrderService.eventTypeText("ORDER_NEW_EVENT"));
    }
}
