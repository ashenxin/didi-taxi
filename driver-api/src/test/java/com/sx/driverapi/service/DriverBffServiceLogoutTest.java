package com.sx.driverapi.service;

import com.sx.driverapi.client.CapacityDriverClient;
import com.sx.driverapi.client.CoreResponseVo;
import com.sx.driverapi.client.OrderClient;
import com.sx.driverapi.client.PassengerNotifyClient;
import com.sx.driverapi.model.ordercore.TripOrderRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        when(orderClient.driverCancelBeforeArrive(eq("O1"), eq(String.valueOf(driverId)), any())).thenReturn(ok(null));
        when(orderClient.driverCancelBeforeArrive(eq("O2"), eq(String.valueOf(driverId)), any())).thenReturn(ok(null));
        when(orderClient.getByOrderNo("O1")).thenReturn(ok(order("O1", 1001L)));
        when(orderClient.getByOrderNo("O2")).thenReturn(ok(order("O2", 1002L)));
        when(passengerNotifyClient.orderChanged(any())).thenReturn(ok(null));

        service.releaseAcceptedBeforeArriveOnLogout(driverId);

        verify(orderClient).driverCancelBeforeArrive(eq("O1"), eq(String.valueOf(driverId)),
                org.mockito.ArgumentMatchers.argThat(body ->
                        body != null
                                && Long.valueOf(driverId).equals(body.getDriverId())
                                && DriverBffService.REASON_DRIVER_LOGOUT.equals(body.getReasonCode())));
        verify(orderClient).driverCancelBeforeArrive(eq("O2"), eq(String.valueOf(driverId)), any());
        verify(passengerNotifyClient, times(2)).orderChanged(any());
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
