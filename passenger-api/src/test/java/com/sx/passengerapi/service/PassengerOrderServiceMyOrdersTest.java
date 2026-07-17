package com.sx.passengerapi.service;

import com.sx.passengerapi.client.CalculateClient;
import com.sx.passengerapi.client.CapacityDispatchClient;
import com.sx.passengerapi.client.MapClient;
import com.sx.passengerapi.client.OrderClient;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.order.OrderStatus;
import com.sx.passengerapi.model.order.PassengerOrderListType;
import com.sx.passengerapi.model.order.PassengerOrderPageVO;
import com.sx.passengerapi.model.ordercore.OrderPageData;
import com.sx.passengerapi.model.ordercore.TripOrderRow;
import com.sx.passengerapi.ws.PassengerWsNotifyService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PassengerOrderServiceMyOrdersTest {

    private final MapClient mapClient = mock(MapClient.class);
    private final CalculateClient calculateClient = mock(CalculateClient.class);
    private final OrderClient orderClient = mock(OrderClient.class);
    private final CapacityDispatchClient capacityDispatchClient = mock(CapacityDispatchClient.class);
    private final PassengerWsNotifyService wsNotifyService = mock(PassengerWsNotifyService.class);
    private final PassengerOrderService service = new PassengerOrderService(
            mapClient, calculateClient, orderClient, capacityDispatchClient, wsNotifyService);

    @Test
    void listMyOrdersReturnsAllRowsInDescendingOrderAndAddsButtonPlaceholders() {
        when(orderClient.pageOrders(eq(10001L), eq(1), eq(100))).thenReturn(ResponseVo.success(pageData(
                4,
                row("O-1", 0, "2026-06-30T10:00:00"),
                row("O-2", 4, "2026-06-30T09:00:00")
        )));
        when(orderClient.pageOrders(eq(10001L), eq(2), eq(100))).thenReturn(ResponseVo.success(pageData(
                4,
                row("O-3", 6, "2026-06-30T08:00:00"),
                row("O-4", 3, "2026-06-30T07:00:00")
        )));

        PassengerOrderPageVO page = service.listMyOrders(10001L, PassengerOrderListType.ALL, 1, 2);

        assertThat(page.getType()).isEqualTo(PassengerOrderListType.ALL);
        assertThat(page.getTotal()).isEqualTo(4);
        assertThat(page.getPageNo()).isEqualTo(1);
        assertThat(page.getPageSize()).isEqualTo(2);
        assertThat(page.getList()).extracting("orderNo").containsExactly("O-1", "O-2");
        assertThat(page.getList().get(0).getActions()).hasSize(3);
        assertThat(page.getList().get(0).getActions()).extracting("label")
                .containsExactly("申请开票", "呼叫返程", "评价");
    }

    @Test
    void listMyOrdersFiltersToDepartOrders() {
        when(orderClient.pageOrders(eq(10001L), eq(1), eq(100))).thenReturn(ResponseVo.success(pageData(
                4,
                row("O-1", 0, "2026-06-30T10:00:00"),
                row("O-2", 4, "2026-06-30T09:00:00")
        )));
        when(orderClient.pageOrders(eq(10001L), eq(2), eq(100))).thenReturn(ResponseVo.success(pageData(
                4,
                row("O-3", 6, "2026-06-30T08:00:00"),
                row("O-4", 3, "2026-06-30T07:00:00")
        )));

        PassengerOrderPageVO page = service.listMyOrders(10001L, PassengerOrderListType.TO_DEPART, 1, 10);

        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getList()).extracting("orderNo").containsExactly("O-1", "O-4");
        assertThat(page.getList()).extracting("status")
                .containsExactly(OrderStatus.CREATED, OrderStatus.ARRIVED);
    }

    @Test
    void listMyOrdersFiltersRefundAndCancelOrders() {
        when(orderClient.pageOrders(eq(10001L), eq(1), eq(100))).thenReturn(ResponseVo.success(pageData(
                3,
                row("O-1", 0, "2026-06-30T10:00:00"),
                row("O-2", 6, "2026-06-30T09:00:00")
        )));
        when(orderClient.pageOrders(eq(10001L), eq(2), eq(100))).thenReturn(ResponseVo.success(pageData(
                3,
                row("O-3", 5, "2026-06-30T08:00:00")
        )));

        PassengerOrderPageVO page = service.listMyOrders(10001L, PassengerOrderListType.REFUND_CANCEL, 1, 10);

        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getList()).extracting("orderNo").containsExactly("O-2");
        assertThat(page.getList().get(0).getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    private static OrderPageData pageData(int total, TripOrderRow... rows) {
        OrderPageData data = new OrderPageData();
        data.setTotal(total);
        data.setPageNo(1);
        data.setPageSize(100);
        data.setList(List.of(rows));
        return data;
    }

    private static TripOrderRow row(String orderNo, int status, String createdAt) {
        TripOrderRow row = new TripOrderRow();
        row.setOrderNo(orderNo);
        row.setPassengerId(10001L);
        row.setStatus(status);
        row.setOriginAddress("起点-" + orderNo);
        row.setDestAddress("终点-" + orderNo);
        row.setCreatedAt(LocalDateTime.parse(createdAt));
        row.setEstimatedAmount(BigDecimal.valueOf(12.34));
        row.setFinalAmount(BigDecimal.valueOf(23.45));
        return row;
    }
}
