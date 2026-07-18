package com.sx.capacity.service;

import com.sx.capacity.client.order.OrderServiceClient;
import com.sx.capacity.client.order.OrderServiceResponseVo;
import com.sx.capacity.client.order.dto.AssignOrderFeignBody;
import com.sx.capacity.client.order.dto.AssignedAwaitingRescheduleFeignDto;
import com.sx.capacity.client.order.dto.OpenDriverOfferFeignBody;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OfferRescheduleServiceTest {

    private static final String ORDER_NO = "ORDER-1";
    private static final long OLD_DRIVER_ID = 80001L;

    private final OrderServiceClient orderServiceClient = mock(OrderServiceClient.class);
    private final DriverGeoRedisPool driverGeoRedisPool = mock(DriverGeoRedisPool.class);
    private final NearestDriverQueryService nearestDriverQueryService = mock(NearestDriverQueryService.class);
    private final DispatchOrderPoolService dispatchOrderPoolService = mock(DispatchOrderPoolService.class);
    private final OfferRescheduleService service = new OfferRescheduleService(
            orderServiceClient, driverGeoRedisPool, nearestDriverQueryService, dispatchOrderPoolService,
            3000, 30, 50, 2);

    @Test
    void incompleteOfferRoundsReopenWindowForSameDriver() {
        when(orderServiceClient.listAssignedAwaitingReschedule(50))
                .thenReturn(response(200, List.of(order(1))));
        when(orderServiceClient.openDriverOffer(eq(ORDER_NO), any())).thenReturn(response(200, null));

        int advanced = service.processRescheduleBatch();

        assertThat(advanced).isEqualTo(1);
        ArgumentCaptor<OpenDriverOfferFeignBody> offerCaptor = ArgumentCaptor.forClass(OpenDriverOfferFeignBody.class);
        verify(orderServiceClient).openDriverOffer(eq(ORDER_NO), offerCaptor.capture());
        assertThat(offerCaptor.getValue().getOfferSeconds()).isEqualTo(30);
        verify(dispatchOrderPoolService).addPending(OLD_DRIVER_ID, ORDER_NO);
        verify(orderServiceClient, never()).reassign(eq(ORDER_NO), any());
        verifyNoInteractions(driverGeoRedisPool, nearestDriverQueryService);
    }

    @Test
    void completedOfferRoundsRedispatchToAnotherEligibleDriver() {
        long newDriverId = 80002L;
        when(orderServiceClient.listAssignedAwaitingReschedule(50))
                .thenReturn(response(200, List.of(order(2))));
        when(driverGeoRedisPool.listNearestDriverIds("330100", 30.25, 120.21, 3000, 32))
                .thenReturn(List.of(OLD_DRIVER_ID, newDriverId));
        when(nearestDriverQueryService.buildEligibleForDriver(newDriverId, "330100", "ECONOMY"))
                .thenReturn(candidate(newDriverId));
        when(orderServiceClient.reassign(eq(ORDER_NO), any())).thenReturn(response(200, null));
        when(orderServiceClient.openDriverOffer(eq(ORDER_NO), any())).thenReturn(response(200, null));

        int advanced = service.processRescheduleBatch();

        assertThat(advanced).isEqualTo(1);
        ArgumentCaptor<AssignOrderFeignBody> assignCaptor = ArgumentCaptor.forClass(AssignOrderFeignBody.class);
        verify(orderServiceClient).reassign(eq(ORDER_NO), assignCaptor.capture());
        assertThat(assignCaptor.getValue().getDriverId()).isEqualTo(newDriverId);
        assertThat(assignCaptor.getValue().getCarId()).isEqualTo(90002L);
        assertThat(assignCaptor.getValue().getCompanyId()).isEqualTo(70002L);
        verify(dispatchOrderPoolService).removePending(OLD_DRIVER_ID, ORDER_NO);
        verify(dispatchOrderPoolService).addPending(newDriverId, ORDER_NO);
        verify(orderServiceClient).openDriverOffer(eq(ORDER_NO), any());
    }

    @Test
    void failedReassignDoesNotMovePendingOrderIndex() {
        long newDriverId = 80002L;
        when(orderServiceClient.listAssignedAwaitingReschedule(50))
                .thenReturn(response(200, List.of(order(2))));
        when(driverGeoRedisPool.listNearestDriverIds("330100", 30.25, 120.21, 3000, 32))
                .thenReturn(List.of(OLD_DRIVER_ID, newDriverId));
        when(nearestDriverQueryService.buildEligibleForDriver(newDriverId, "330100", "ECONOMY"))
                .thenReturn(candidate(newDriverId));
        when(orderServiceClient.reassign(eq(ORDER_NO), any())).thenReturn(response(409, null));

        int advanced = service.processRescheduleBatch();

        assertThat(advanced).isZero();
        verify(dispatchOrderPoolService, never()).removePending(any(), any());
        verify(dispatchOrderPoolService, never()).addPending(any(), any());
        verify(orderServiceClient, never()).openDriverOffer(eq(ORDER_NO), any());
    }

    private static AssignedAwaitingRescheduleFeignDto order(int offerRound) {
        AssignedAwaitingRescheduleFeignDto row = new AssignedAwaitingRescheduleFeignDto();
        row.setOrderNo(ORDER_NO);
        row.setCityCode("330100");
        row.setProductCode("ECONOMY");
        row.setOriginLat(new BigDecimal("30.25"));
        row.setOriginLng(new BigDecimal("120.21"));
        row.setDriverId(OLD_DRIVER_ID);
        row.setOfferRound(offerRound);
        return row;
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
