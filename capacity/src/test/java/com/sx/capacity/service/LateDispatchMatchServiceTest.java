package com.sx.capacity.service;

import com.sx.capacity.client.order.OrderServiceClient;
import com.sx.capacity.client.order.OrderServiceResponseVo;
import com.sx.capacity.client.order.dto.AssignOrderFeignBody;
import com.sx.capacity.client.order.dto.PendingDispatchFeignDto;
import com.sx.capacity.model.dto.NearestDriverResult;
import com.sx.capacity.service.geo.DriverGeoRedisPool;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LateDispatchMatchServiceTest {

    private final OrderServiceClient orderServiceClient = mock(OrderServiceClient.class);
    private final DriverGeoRedisPool driverGeoRedisPool = mock(DriverGeoRedisPool.class);
    private final NearestDriverQueryService nearestDriverQueryService = mock(NearestDriverQueryService.class);
    private final DispatchOrderPoolService dispatchOrderPoolService = mock(DispatchOrderPoolService.class);
    private final DriverPassengerMatchBlockService matchBlockService = mock(DriverPassengerMatchBlockService.class);
    private final LateDispatchMatchService service = new LateDispatchMatchService(
            orderServiceClient, driverGeoRedisPool, nearestDriverQueryService,
            dispatchOrderPoolService, matchBlockService, 3000, 30, 50);

    @Test
    void scheduledScanSkipsBlockedDriverAndAssignsNextCandidate() {
        PendingDispatchFeignDto order = order();
        when(orderServiceClient.listPendingDispatchAll(50)).thenReturn(response(200, List.of(order)));
        when(driverGeoRedisPool.listNearestDriverIds("330100", 30.25, 120.21, 3000, 32))
                .thenReturn(List.of(80001L, 80002L));
        when(nearestDriverQueryService.buildEligibleForDriver(80001L, "330100", "ECONOMY"))
                .thenReturn(candidate(80001L));
        when(nearestDriverQueryService.buildEligibleForDriver(80002L, "330100", "ECONOMY"))
                .thenReturn(candidate(80002L));
        when(matchBlockService.isBlocked(80001L, 10001L)).thenReturn(true);
        when(matchBlockService.isBlocked(80002L, 10001L)).thenReturn(false);
        when(orderServiceClient.assign(eq("ORDER-1"), any())).thenReturn(response(200, null));
        when(orderServiceClient.openDriverOffer(eq("ORDER-1"), any())).thenReturn(response(200, null));

        int matched = service.tryMatchScheduledScan();

        assertThat(matched).isEqualTo(1);
        ArgumentCaptor<AssignOrderFeignBody> bodyCaptor = ArgumentCaptor.forClass(AssignOrderFeignBody.class);
        verify(orderServiceClient).assign(eq("ORDER-1"), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue().getDriverId()).isEqualTo(80002L);
        verify(dispatchOrderPoolService).addPending(80002L, "ORDER-1");
        verify(orderServiceClient, never()).assign(eq("ORDER-1"),
                org.mockito.ArgumentMatchers.argThat(body -> body.getDriverId().equals(80001L)));
    }

    @Test
    void failedAssignDoesNotAddOrderToDriverPendingIndex() {
        PendingDispatchFeignDto order = order();
        when(orderServiceClient.listPendingDispatchAll(50)).thenReturn(response(200, List.of(order)));
        when(driverGeoRedisPool.listNearestDriverIds("330100", 30.25, 120.21, 3000, 32))
                .thenReturn(List.of(80001L));
        when(nearestDriverQueryService.buildEligibleForDriver(80001L, "330100", "ECONOMY"))
                .thenReturn(candidate(80001L));
        when(matchBlockService.isBlocked(80001L, 10001L)).thenReturn(false);
        when(orderServiceClient.assign(eq("ORDER-1"), any())).thenReturn(response(409, null));

        int matched = service.tryMatchScheduledScan();

        assertThat(matched).isZero();
        verify(dispatchOrderPoolService, never()).addPending(any(), any());
        verify(orderServiceClient, never()).openDriverOffer(eq("ORDER-1"), any());
    }

    private static PendingDispatchFeignDto order() {
        PendingDispatchFeignDto order = new PendingDispatchFeignDto();
        order.setOrderNo("ORDER-1");
        order.setPassengerId(10001L);
        order.setCityCode("330100");
        order.setProductCode("ECONOMY");
        order.setOriginLat(new BigDecimal("30.25"));
        order.setOriginLng(new BigDecimal("120.21"));
        return order;
    }

    private static NearestDriverResult candidate(Long driverId) {
        NearestDriverResult result = new NearestDriverResult();
        result.setDriverId(driverId);
        result.setCarId(driverId + 10000);
        result.setCompanyId(driverId - 10000);
        return result;
    }

    private static <T> OrderServiceResponseVo<T> response(int code, T data) {
        OrderServiceResponseVo<T> response = new OrderServiceResponseVo<>();
        response.setCode(code);
        response.setData(data);
        return response;
    }
}
