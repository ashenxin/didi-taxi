package com.sx.driverapi.service;

import com.sx.driverapi.client.CapacityDriverClient;
import com.sx.driverapi.client.CapacityTeamChangeClient;
import com.sx.driverapi.client.CoreResponseVo;
import com.sx.driverapi.common.exception.BizErrorException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriverTeamChangeBffServiceTest {

    private final CapacityTeamChangeClient teamChangeClient = mock(CapacityTeamChangeClient.class);
    private final CapacityDriverClient driverClient = mock(CapacityDriverClient.class);
    private final DriverTeamChangeBffService service = new DriverTeamChangeBffService(teamChangeClient, driverClient);

    @Test
    void submitUsesAuthenticatedDriverInDownstreamBody() {
        CoreResponseVo<Map<String, Object>> response = response(200, null);
        response.setData(Map.of("requestId", 88L));
        when(teamChangeClient.submit(any())).thenReturn(response);

        Long requestId = service.submit(80001L, 30001L, "申请换队");

        assertThat(requestId).isEqualTo(88L);
        verify(teamChangeClient).submit(org.mockito.ArgumentMatchers.argThat(body ->
                body.get("driverId").equals(80001L)
                        && body.get("requestedBy").equals("80001")
                        && body.get("toTeamId").equals(30001L)));
    }

    @Test
    void pendingRequestConflictMapsToHttp409BusinessError() {
        when(teamChangeClient.submit(any())).thenReturn(response(400, "已有待审核申请"));

        BizErrorException error = assertThrows(BizErrorException.class,
                () -> service.submit(80001L, 30001L, "申请换队"));

        assertThat(error.getErrorCode()).isEqualTo(409);
    }

    @Test
    void cancelOwnershipViolationMapsToHttp403BusinessError() {
        when(teamChangeClient.cancel(org.mockito.ArgumentMatchers.eq(88L), any()))
                .thenReturn(response(400, "禁止操作其他司机数据"));

        BizErrorException error = assertThrows(BizErrorException.class,
                () -> service.cancel(80001L, 88L));

        assertThat(error.getErrorCode()).isEqualTo(403);
    }

    private static <T> CoreResponseVo<T> response(int code, String message) {
        CoreResponseVo<T> response = new CoreResponseVo<>();
        response.setCode(code);
        response.setMsg(message);
        return response;
    }
}
