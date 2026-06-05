package com.sx.passengerapi.service;

import com.sx.passengerapi.client.CalculateClient;
import com.sx.passengerapi.client.CapacityDispatchClient;
import com.sx.passengerapi.client.MapClient;
import com.sx.passengerapi.client.OrderClient;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.map.RouteResponse;
import com.sx.passengerapi.model.order.CreateAndAssignOrderBody;
import com.sx.passengerapi.model.order.OrderStatus;
import com.sx.passengerapi.model.order.Place;
import com.sx.passengerapi.model.ordercore.CreateOrderResult;
import com.sx.passengerapi.ws.PassengerWsNotifyService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PassengerOrderServiceAsyncCreateTest {

    private final MapClient mapClient = mock(MapClient.class);
    private final CalculateClient calculateClient = mock(CalculateClient.class);
    private final OrderClient orderClient = mock(OrderClient.class);
    private final CapacityDispatchClient capacityDispatchClient = mock(CapacityDispatchClient.class);
    private final PassengerWsNotifyService wsNotifyService = mock(PassengerWsNotifyService.class);
    private final PassengerOrderService service = new PassengerOrderService(
            mapClient, calculateClient, orderClient, capacityDispatchClient, 30, wsNotifyService);

    @Test
    void createAndAssignOnlyCreatesOrderAndLeavesDispatchToAsyncOutbox() {
        RouteResponse route = new RouteResponse();
        route.setDistanceMeters(12_000L);
        route.setDurationSeconds(1_500L);
        when(mapClient.drivingRoute(any())).thenReturn(ResponseVo.success(route));
        when(capacityDispatchClient.nearestDriver(anyString(), anyString(), anyDouble(), anyDouble(), anyLong()))
                .thenReturn(new ResponseVo<>(404, "no driver", null));
        CreateOrderResult created = new CreateOrderResult();
        created.setOrderNo("O-ASYNC-1");
        when(orderClient.create(anyString(), any())).thenReturn(ResponseVo.success(created));

        var result = service.createAndAssign(body(), "idem-async-1");

        assertThat(result.getOrderNo()).isEqualTo("O-ASYNC-1");
        assertThat(result.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(result.getAssignedDriver()).isNull();
        verify(orderClient).create(anyString(), any());
        verify(orderClient, never()).assign(anyString(), any());
        verify(orderClient, never()).openDriverOffer(anyString(), any());
        verify(capacityDispatchClient, never()).addPendingOrderIndex(any());
        verify(wsNotifyService).notifyOrderChanged(10001L, "O-ASYNC-1");
    }

    private static CreateAndAssignOrderBody body() {
        CreateAndAssignOrderBody body = new CreateAndAssignOrderBody();
        body.setPassengerId(10001L);
        body.setProvinceCode("330000");
        body.setCityCode("330100");
        body.setProductCode("ECONOMY");
        body.setOrigin(place("杭州东站", 30.2912, 120.212));
        body.setDest(place("龙翔桥", 30.2592, 120.164));
        return body;
    }

    private static Place place(String name, double lat, double lng) {
        Place p = new Place();
        p.setName(name);
        p.setAddress(name);
        p.setLat(lat);
        p.setLng(lng);
        return p;
    }
}
