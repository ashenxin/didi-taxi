package com.sx.passengerapi.service;

import com.sx.passengerapi.client.CalculateClient;
import com.sx.passengerapi.client.CapacityDispatchClient;
import com.sx.passengerapi.client.MapClient;
import com.sx.passengerapi.client.OrderClient;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.calculate.EstimateFareResult;
import com.sx.passengerapi.model.capacity.NearestDriverResult;
import com.sx.passengerapi.model.map.RouteResponse;
import com.sx.passengerapi.model.order.CreateAndAssignOrderBody;
import com.sx.passengerapi.model.order.OrderStatus;
import com.sx.passengerapi.model.order.Place;
import com.sx.passengerapi.model.ordercore.CreateOrderResult;
import com.sx.passengerapi.model.ordercore.CreateOrderBody;
import com.sx.passengerapi.ws.PassengerWsNotifyService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class PassengerOrderServiceAsyncCreateTest {

    private final MapClient mapClient = mock(MapClient.class);
    private final CalculateClient calculateClient = mock(CalculateClient.class);
    private final OrderClient orderClient = mock(OrderClient.class);
    private final CapacityDispatchClient capacityDispatchClient = mock(CapacityDispatchClient.class);
    private final PassengerWsNotifyService wsNotifyService = mock(PassengerWsNotifyService.class);
    private final PassengerOrderService service = new PassengerOrderService(
            mapClient, calculateClient, orderClient, capacityDispatchClient, wsNotifyService);

    @Test
    void createAndAssignOnlyCreatesOrderAndLeavesDispatchToAsyncOutbox() {
        when(orderClient.blockingOrder(10001L, "idem-async-1")).thenReturn(ResponseVo.success(null));
        RouteResponse route = new RouteResponse();
        route.setDistanceMeters(12_000L);
        route.setDurationSeconds(1_500L);
        route.setProvider("LOCAL_MOCK_ROUTE");
        route.setVersion("mock-route-v1");
        when(mapClient.drivingRoute(any())).thenReturn(ResponseVo.success(route));
        NearestDriverResult nearest = new NearestDriverResult();
        nearest.setDriverId(80001L);
        nearest.setCompanyId(9L);
        when(capacityDispatchClient.nearestDriver(anyString(), anyString(), anyDouble(), anyDouble(), anyLong()))
                .thenReturn(ResponseVo.success(nearest));
        EstimateFareResult estimate = new EstimateFareResult();
        estimate.setRuleId(7L);
        estimate.setEstimatedAmount(new BigDecimal("35.00"));
        estimate.setDistanceMeters(12_000L);
        estimate.setDurationSeconds(1_500L);
        estimate.setFareRuleSnapshot("{\"baseFare\":12.00}");
        estimate.setFareCalculationVersion("fare-v1");
        when(calculateClient.estimate(any())).thenReturn(ResponseVo.success(estimate));
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
        ArgumentCaptor<CreateOrderBody> requestCaptor = ArgumentCaptor.forClass(CreateOrderBody.class);
        verify(orderClient).create(anyString(), requestCaptor.capture());
        assertThat(requestCaptor.getValue().getPlannedDistanceMeters()).isEqualTo(12_000L);
        assertThat(requestCaptor.getValue().getPlannedDurationSeconds()).isEqualTo(1_500L);
        assertThat(requestCaptor.getValue().getDistanceSource()).isEqualTo("LOCAL_MOCK_ROUTE");
        assertThat(requestCaptor.getValue().getRouteMockVersion()).isEqualTo("mock-route-v1");
        assertThat(requestCaptor.getValue().getFareRuleSnapshot()).contains("baseFare");
        assertThat(requestCaptor.getValue().getFareCalculationVersion()).isEqualTo("fare-v1");
    }

    @Test
    void missingCoordinatesAreRejectedWithoutExternalGeocoding() {
        when(orderClient.blockingOrder(10001L, "idem-no-coordinates")).thenReturn(ResponseVo.success(null));
        CreateAndAssignOrderBody body = body();
        body.getDest().setLat(null);
        body.getDest().setLng(null);

        assertThatThrownBy(() -> service.createTwoPhase(body, "idem-no-coordinates"))
                .isInstanceOf(BizErrorException.class)
                .satisfies(ex -> assertThat(((BizErrorException) ex).getErrorCode()).isEqualTo(400))
                .hasMessageContaining("经纬度");

        verify(mapClient, never()).drivingRoute(any());
    }

    @Test
    void lostSuccessResponseCanRetryWithSameKeyAndRecoverOriginalOrder() {
        String key = "lost-response-key";
        when(orderClient.blockingOrder(10001L, key)).thenReturn(ResponseVo.success(null));
        RouteResponse route = new RouteResponse();
        route.setDistanceMeters(12_000L);
        route.setDurationSeconds(1_500L);
        route.setProvider("LOCAL_MOCK_ROUTE");
        route.setVersion("mock-route-v1");
        when(mapClient.drivingRoute(any())).thenReturn(ResponseVo.success(route));
        NearestDriverResult nearest = new NearestDriverResult();
        nearest.setCompanyId(9L);
        when(capacityDispatchClient.nearestDriver(anyString(), anyString(), anyDouble(), anyDouble(), anyLong()))
                .thenReturn(ResponseVo.success(nearest));
        EstimateFareResult estimate = new EstimateFareResult();
        estimate.setRuleId(7L);
        estimate.setEstimatedAmount(new BigDecimal("35.00"));
        estimate.setFareRuleSnapshot("{\"baseFare\":12.00}");
        estimate.setFareCalculationVersion("fare-v1");
        when(calculateClient.estimate(any())).thenReturn(ResponseVo.success(estimate));
        CreateOrderResult created = new CreateOrderResult();
        created.setOrderNo("O-ORIGINAL");
        when(orderClient.create(anyString(), any())).thenReturn(ResponseVo.success(created));

        var first = service.createTwoPhase(body(), key);
        var replay = service.createTwoPhase(body(), key);

        assertThat(first.getOrderNo()).isEqualTo("O-ORIGINAL");
        assertThat(replay.getOrderNo()).isEqualTo("O-ORIGINAL");
        verify(orderClient, times(2)).blockingOrder(10001L, key);
        verify(orderClient, times(2)).create(org.mockito.ArgumentMatchers.eq(key),
                org.mockito.ArgumentMatchers.any());
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
