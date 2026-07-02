package com.sx.passengerapi.controller;

import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.model.order.PassengerOrderListType;
import com.sx.passengerapi.model.order.PassengerOrderPageVO;
import com.sx.passengerapi.service.PassengerOrderService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PassengerOrderControllerMyOrdersTest {

    private final PassengerOrderService service = mock(PassengerOrderService.class);
    private final PassengerOrderController controller = new PassengerOrderController(service);

    @Test
    void listOrdersRequiresAuth() {
        assertThatThrownBy(() -> controller.listOrders(null, null, null, null))
                .isInstanceOf(BizErrorException.class)
                .hasMessageContaining("未授权，请重新登录");
    }

    @Test
    void listOrdersPassesTypeAndPaginationToService() {
        PassengerOrderPageVO vo = new PassengerOrderPageVO();
        when(service.listMyOrders(eq(10001L), eq(PassengerOrderListType.TO_DEPART), eq(2), eq(5))).thenReturn(vo);

        var resp = controller.listOrders(10001L, "待出发", 2, 5);

        assertThat(resp.getData()).isSameAs(vo);
        verify(service).listMyOrders(eq(10001L), eq(PassengerOrderListType.TO_DEPART), eq(2), eq(5));
    }
}
