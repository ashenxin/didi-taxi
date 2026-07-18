package com.sx.passengerapi.service;

import com.sx.passengerapi.client.CalculateClient;
import com.sx.passengerapi.client.CapacityDispatchClient;
import com.sx.passengerapi.client.MapClient;
import com.sx.passengerapi.client.OrderClient;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.order.CreateAndAssignOrderBody;
import com.sx.passengerapi.model.order.Place;
import com.sx.passengerapi.model.ordercore.CreateOrderPreflightResult;
import com.sx.passengerapi.model.calculate.EstimateFareResult;
import com.sx.passengerapi.model.map.RouteResponse;
import com.sx.passengerapi.ws.PassengerWsNotifyService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PassengerOrderUnsettledGateTest {
    private final MapClient mapClient = mock(MapClient.class);
    private final CalculateClient calculateClient = mock(CalculateClient.class);
    private final OrderClient orderClient = mock(OrderClient.class);
    private final CapacityDispatchClient capacityClient = mock(CapacityDispatchClient.class);
    private final PassengerOrderService service = new PassengerOrderService(mapClient, calculateClient,
            orderClient, capacityClient, mock(PassengerWsNotifyService.class));

    @Test
    void blockingOrderStopsBeforeRouteAndFareCalls() {
        CreateOrderPreflightResult blocked = new CreateOrderPreflightResult();
        blocked.setDecision("BLOCKED");
        blocked.setOrderNo("OLD-1");
        blocked.setBlockingSettlementStatus("PAYMENT_REQUIRED");
        blocked.setBlockingAction("GO_TO_PAYMENT");
        when(orderClient.createPreflight(org.mockito.ArgumentMatchers.eq("key"), any()))
                .thenReturn(ResponseVo.success(blocked));

        BizErrorException error = catchThrowableOfType(
                () -> service.createTwoPhase(body(), "key"), BizErrorException.class);

        assertThat(error.getErrorCode()).isEqualTo(409);
        assertThat(error).hasMessageContaining("待支付");
        verify(mapClient, never()).drivingRoute(any());
        verify(calculateClient, never()).estimate(any());
    }

    @Test
    void precheckFailureClosesBookingFlow() {
        when(orderClient.createPreflight(org.mockito.ArgumentMatchers.eq("key"), any()))
                .thenThrow(new IllegalStateException("order unavailable"));

        BizErrorException error = catchThrowableOfType(
                () -> service.createTwoPhase(body(), "key"), BizErrorException.class);

        assertThat(error.getErrorCode()).isEqualTo(502);
        verify(mapClient, never()).drivingRoute(any());
    }

    @Test
    void nullPreflightDecisionClosesBeforeRouteCall() {
        when(orderClient.createPreflight(org.mockito.ArgumentMatchers.eq("key"), any()))
                .thenReturn(ResponseVo.success(null));

        BizErrorException error = catchThrowableOfType(
                () -> service.createTwoPhase(body(), "key"), BizErrorException.class);

        assertThat(error.getErrorCode()).isEqualTo(502);
        assertThat(error).hasMessageContaining("预检");
        verify(mapClient, never()).drivingRoute(any());
    }

    @Test
    void authoritativeOrderConflictRemains409WhenPrecheckLostRace() {
        com.sx.passengerapi.model.ordercore.CreateOrderResult ignoredData =
                new com.sx.passengerapi.model.ordercore.CreateOrderResult();
        when(orderClient.create(any(), any())).thenReturn(new ResponseVo<>(409,
                "您有一笔待支付订单，请结清后再叫车", ignoredData));
        RouteResponse route = new RouteResponse();
        route.setDistanceMeters(10_000L);
        route.setDurationSeconds(1_200L);
        route.setProvider("LOCAL_MOCK_ROUTE");
        route.setVersion("mock-route-v1");
        EstimateFareResult estimate = new EstimateFareResult();
        estimate.setRuleId(1L);
        estimate.setEstimatedAmount(new java.math.BigDecimal("30.00"));
        estimate.setFareRuleSnapshot("{\"baseFare\":12.00}");
        estimate.setFareCalculationVersion("fare-v1");

        BizErrorException error = catchThrowableOfType(
                () -> service.createOrder(body(), route, estimate, "race-key"), BizErrorException.class);

        assertThat(error.getErrorCode()).isEqualTo(409);
        assertThat(error).hasMessageContaining("待支付");
    }

    private static CreateAndAssignOrderBody body() {
        CreateAndAssignOrderBody body = new CreateAndAssignOrderBody();
        body.setPassengerId(10001L);
        body.setProvinceCode("330000");
        body.setCityCode("330100");
        body.setProductCode("ECONOMY");
        body.setOrigin(place("起点", 30.1, 120.1));
        body.setDest(place("终点", 30.2, 120.2));
        return body;
    }

    private static Place place(String name, double lat, double lng) {
        Place place = new Place();
        place.setName(name);
        place.setAddress(name);
        place.setLat(lat);
        place.setLng(lng);
        return place;
    }
}
