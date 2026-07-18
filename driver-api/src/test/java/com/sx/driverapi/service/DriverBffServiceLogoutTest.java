package com.sx.driverapi.service;

import com.sx.driverapi.client.CapacityDriverClient;
import com.sx.driverapi.client.CoreResponseVo;
import com.sx.driverapi.client.OrderClient;
import com.sx.driverapi.client.PassengerNotifyClient;
import com.sx.driverapi.model.ordercore.TripOrderRow;
import com.sx.driverapi.model.ordercore.DriverActionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class DriverBffServiceLogoutTest {

    private final CapacityDriverClient capacityDriverClient = mock(CapacityDriverClient.class);
    private final OrderClient orderClient = mock(OrderClient.class);
    private final PassengerNotifyClient passengerNotifyClient = mock(PassengerNotifyClient.class);
    private final DriverBffService service = new DriverBffService(capacityDriverClient, orderClient, passengerNotifyClient);

    @Test
    void releaseAcceptedBeforeArriveOnLogoutCancelsAcceptedOrdersWithLogoutReason() {
        long driverId = 80001L;
        when(orderClient.listAcceptedBeforeArrive(driverId, String.valueOf(driverId)))
                .thenReturn(ok(List.of(order("O1"), order("O2"))));
        when(orderClient.driverCancelBeforeArrive(eq("O1"), eq(String.valueOf(driverId)), anyString(), any()))
                .thenReturn(ok(new DriverActionResult(false)));
        when(orderClient.driverCancelBeforeArrive(eq("O2"), eq(String.valueOf(driverId)), anyString(), any()))
                .thenReturn(ok(new DriverActionResult(false)));
        when(orderClient.getByOrderNo("O1")).thenReturn(ok(order("O1", 1001L)));
        when(orderClient.getByOrderNo("O2")).thenReturn(ok(order("O2", 1002L)));
        when(passengerNotifyClient.orderChanged(any())).thenReturn(ok(null));

        service.releaseAcceptedBeforeArriveOnLogout(driverId);

        verify(orderClient).driverCancelBeforeArrive(eq("O1"), eq(String.valueOf(driverId)),
                org.mockito.ArgumentMatchers.argThat(key -> key != null && key.startsWith("logout-cancel:")),
                org.mockito.ArgumentMatchers.argThat(body ->
                        body != null
                                && Long.valueOf(driverId).equals(body.getDriverId())
                                && DriverBffService.REASON_DRIVER_LOGOUT.equals(body.getReasonCode())));
        verify(orderClient).driverCancelBeforeArrive(eq("O2"), eq(String.valueOf(driverId)), anyString(), any());
        verify(passengerNotifyClient, times(2)).orderChanged(any());
    }

    @Test
    void successfulRejectReplayStillNotifiesPassengerToRecoverLostFirstResponse() {
        long driverId = 80002L;
        when(orderClient.reject(eq("O-REPLAY"), eq(String.valueOf(driverId)), eq("retry-key"), any()))
                .thenReturn(ok(new DriverActionResult(true)));
        when(orderClient.getByOrderNo("O-REPLAY")).thenReturn(ok(order("O-REPLAY", 1003L)));
        when(passengerNotifyClient.orderChanged(any())).thenReturn(ok(null));

        DriverActionResult result = service.reject("O-REPLAY", driverId, "TOO_FAR", "retry-key");

        assertThat(result.replayed()).isTrue();
        verify(passengerNotifyClient).orderChanged(org.mockito.ArgumentMatchers.argThat(body ->
                body != null && Long.valueOf(1003L).equals(body.getPassengerId())
                        && "O-REPLAY".equals(body.getOrderNo())));
    }

    private static TripOrderRow order(String orderNo) {
        return order(orderNo, null);
    }

    private static TripOrderRow order(String orderNo, Long passengerId) {
        TripOrderRow row = new TripOrderRow();
        row.setOrderNo(orderNo);
        row.setPassengerId(passengerId);
        return row;
    }

    private static <T> CoreResponseVo<T> ok(T data) {
        CoreResponseVo<T> resp = new CoreResponseVo<>();
        resp.setCode(200);
        resp.setMsg("success");
        resp.setData(data);
        return resp;
    }
}
