package com.sx.passengerapi.service;

import com.sx.passengerapi.client.CalculateClient;
import com.sx.passengerapi.client.CapacityDispatchClient;
import com.sx.passengerapi.client.MapClient;
import com.sx.passengerapi.client.OrderClient;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.order.CancelOrderRequest;
import com.sx.passengerapi.model.ordercore.OrderActionResult;
import com.sx.passengerapi.model.ordercore.OrderPageData;
import com.sx.passengerapi.model.ordercore.TripOrderRow;
import com.sx.passengerapi.ws.PassengerWsNotifyService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PassengerOrderServiceCancelIdempotencyTest {

    private final OrderClient orderClient = mock(OrderClient.class);
    private final PassengerWsNotifyService notifyService = mock(PassengerWsNotifyService.class);
    private final PassengerOrderService service = new PassengerOrderService(
            mock(MapClient.class), mock(CalculateClient.class), orderClient,
            mock(CapacityDispatchClient.class), notifyService);

    @Test
    void replayedCancelIsReturnedAndStillRefreshesPassenger() {
        when(orderClient.cancel(eq("O1"), eq("cancel-key"), any()))
                .thenReturn(ResponseVo.success(new OrderActionResult(true)));
        CancelOrderRequest request = new CancelOrderRequest();
        request.setPassengerId(10001L);
        request.setCancelReason("行程有变");

        OrderActionResult result = service.cancelOrder("O1", request, "cancel-key");

        assertThat(result.replayed()).isTrue();
        verify(notifyService).notifyOrderChanged(10001L, "O1");
    }

    @Test
    void logoutCancelUsesAUniqueInternalIdempotencyKey() {
        TripOrderRow row = new TripOrderRow();
        row.setOrderNo("O2");
        row.setPassengerId(10001L);
        row.setStatus(0);
        OrderPageData page = new OrderPageData();
        page.setList(List.of(row));
        page.setTotal(1);
        when(orderClient.pageOrders(10001L, 1, 100)).thenReturn(ResponseVo.success(page));
        when(orderClient.cancel(eq("O2"),
                argThat(key -> key != null && key.startsWith("passenger-logout-cancel:")), any()))
                .thenReturn(ResponseVo.success(new OrderActionResult(false)));

        var result = service.cancelInFlightOrdersOnPassengerLogout(10001L);

        assertThat(result.getHint()).contains("已为您取消");
        verify(orderClient).cancel(eq("O2"),
                argThat(key -> key != null && key.startsWith("passenger-logout-cancel:")), any());
    }
}
