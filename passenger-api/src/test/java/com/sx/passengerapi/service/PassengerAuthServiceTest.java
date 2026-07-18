package com.sx.passengerapi.service;

import com.sx.passengerapi.auth.AppJwtService;
import com.sx.passengerapi.auth.PassengerTokenVersionStore;
import com.sx.passengerapi.client.PassengerCoreAuthClient;
import com.sx.passengerapi.client.dto.AppAuthCustomerBrief;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.auth.CustomerLoginResponse;
import com.sx.passengerapi.ws.PassengerWsProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PassengerAuthServiceTest {

    private final PassengerCoreAuthClient coreAuthClient = mock(PassengerCoreAuthClient.class);
    private final AppJwtService jwtService = mock(AppJwtService.class);
    private final PassengerTokenVersionStore tokenVersionStore = mock(PassengerTokenVersionStore.class);
    private final PassengerOrderService passengerOrderService = mock(PassengerOrderService.class);
    private final PassengerWsProperties wsProperties = new PassengerWsProperties();
    private final PassengerAuthService service = new PassengerAuthService(
            coreAuthClient, jwtService, tokenVersionStore, passengerOrderService, wsProperties);

    @Test
    void passwordLoginIssuesAuditOneTokenWithNewTokenVersion() {
        AppAuthCustomerBrief customer = new AppAuthCustomerBrief();
        customer.setId(10001L);
        customer.setPhone("13812345678");
        customer.setNickname("乘客");
        when(coreAuthClient.loginPassword(any())).thenReturn(ResponseVo.success(customer));
        when(tokenVersionStore.nextVersion(10001L)).thenReturn(7L);
        when(jwtService.createPassengerToken(10001L, "13812345678", 7L, 1)).thenReturn("token-1");
        when(jwtService.getExpirationSeconds()).thenReturn(7200L);

        CustomerLoginResponse response = service.loginPassword("13812345678", "secret");

        assertThat(response.getAccessToken()).isEqualTo("token-1");
        assertThat(response.getCustomer().getId()).isEqualTo(10001L);
        verify(jwtService).createPassengerToken(10001L, "13812345678", 7L, 1);
    }

    @Test
    void websocketTokenIsRejectedWhenRealtimeChannelDisabled() {
        wsProperties.setEnabled(false);

        BizErrorException error = assertThrows(BizErrorException.class,
                () -> service.issueWsToken(10001L));

        assertThat(error.getErrorCode()).isEqualTo(503);
        verify(tokenVersionStore, never()).currentVersion(10001L);
    }

    @Test
    void websocketTokenRequiresCurrentLoginVersion() {
        wsProperties.setEnabled(true);
        when(tokenVersionStore.currentVersion(10001L)).thenReturn(null);

        BizErrorException error = assertThrows(BizErrorException.class,
                () -> service.issueWsToken(10001L));

        assertThat(error.getErrorCode()).isEqualTo(401);
        verify(jwtService, never()).createPassengerToken(any(Long.class), any(), any(Long.class), any(Integer.class));
    }
}
