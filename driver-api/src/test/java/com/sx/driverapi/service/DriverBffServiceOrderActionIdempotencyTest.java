package com.sx.driverapi.service;

import com.sx.driverapi.client.CapacityDriverClient;
import com.sx.driverapi.client.CoreResponseVo;
import com.sx.driverapi.client.OrderClient;
import com.sx.driverapi.client.PassengerNotifyClient;
import com.sx.driverapi.model.order.FinishOrderBody;
import com.sx.driverapi.model.ordercore.AcceptOrderPreflightResult;
import com.sx.driverapi.model.ordercore.DriverActionResult;
import com.sx.driverapi.model.ordercore.TripOrderRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriverBffServiceOrderActionIdempotencyTest {

    private static final String ORDER_NO = "ORDER-IDEMPOTENT-1";
    private static final long DRIVER_ID = 80001L;

    private final CapacityDriverClient capacityClient = mock(CapacityDriverClient.class);
    private final OrderClient orderClient = mock(OrderClient.class);
    private final PassengerNotifyClient notifyClient = mock(PassengerNotifyClient.class);
    private final DriverBffService service = new DriverBffService(capacityClient, orderClient, notifyClient);

    @BeforeEach
    void stubPassengerRefreshDependencies() {
        TripOrderRow row = new TripOrderRow();
        row.setPassengerId(10001L);
        when(orderClient.getByOrderNo(ORDER_NO)).thenReturn(success(row));
        when(notifyClient.orderChanged(any())).thenReturn(success(null));
    }

    @Test
    void acceptReplaySkipsCapacityAndFinalWriteButStillRefreshesPassenger() {
        when(orderClient.acceptPreflight(eq(ORDER_NO), eq(String.valueOf(DRIVER_ID)),
                eq("accept-key"), any())).thenReturn(success(new AcceptOrderPreflightResult(true)));

        DriverActionResult result = service.accept(ORDER_NO, DRIVER_ID, "accept-key");

        assertTrue(result.replayed());
        verify(capacityClient, never()).acceptReadiness(DRIVER_ID);
        verify(orderClient, never()).accept(eq(ORDER_NO), eq(String.valueOf(DRIVER_ID)),
                eq("accept-key"), any());
        verify(notifyClient).orderChanged(any());
    }

    @Test
    void firstAcceptChecksCapacityThenPropagatesKeyToFinalWrite() {
        when(orderClient.acceptPreflight(eq(ORDER_NO), eq(String.valueOf(DRIVER_ID)),
                eq("accept-key"), any())).thenReturn(success(new AcceptOrderPreflightResult(false)));
        when(capacityClient.acceptReadiness(DRIVER_ID)).thenReturn(success(null));
        when(orderClient.accept(eq(ORDER_NO), eq(String.valueOf(DRIVER_ID)),
                eq("accept-key"), any())).thenReturn(success(new DriverActionResult(false)));

        DriverActionResult result = service.accept(ORDER_NO, DRIVER_ID, "accept-key");

        assertFalse(result.replayed());
        verify(capacityClient).acceptReadiness(DRIVER_ID);
        verify(orderClient).accept(eq(ORDER_NO), eq(String.valueOf(DRIVER_ID)),
                eq("accept-key"), any());
        verify(notifyClient).orderChanged(any());
    }

    @Test
    void arriveStartAndFinishPropagateTheirKeysAndReplayResults() {
        when(orderClient.arrive(eq(ORDER_NO), eq(String.valueOf(DRIVER_ID)),
                eq("arrive-key"), any())).thenReturn(success(new DriverActionResult(true)));
        when(orderClient.start(eq(ORDER_NO), eq(String.valueOf(DRIVER_ID)),
                eq("start-key"), any())).thenReturn(success(new DriverActionResult(false)));
        when(orderClient.finish(eq(ORDER_NO), eq(String.valueOf(DRIVER_ID)),
                eq("finish-key"), any())).thenReturn(success(new DriverActionResult(true)));
        FinishOrderBody finish = new FinishOrderBody();
        finish.setDriverId(DRIVER_ID);

        assertTrue(service.arrive(ORDER_NO, DRIVER_ID, "arrive-key").replayed());
        assertFalse(service.start(ORDER_NO, DRIVER_ID, "start-key").replayed());
        assertTrue(service.finish(ORDER_NO, finish, "finish-key").replayed());

        verify(notifyClient, org.mockito.Mockito.times(3)).orderChanged(any());
    }

    private static <T> CoreResponseVo<T> success(T data) {
        CoreResponseVo<T> response = new CoreResponseVo<>();
        response.setCode(200);
        response.setData(data);
        return response;
    }
}
