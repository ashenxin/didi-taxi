package com.sx.passengerapi.controller;

import com.sx.passengerapi.auth.PassengerAuthContext;
import com.sx.passengerapi.model.auth.CustomerLoginResponse;
import com.sx.passengerapi.model.auth.PassengerLogoutResult;
import com.sx.passengerapi.service.PassengerAuthService;
import org.junit.jupiter.api.Test;

import static com.sx.passengerapi.auth.PassengerSessionScope.NORMAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PassengerAuthControllerTest {

    private final PassengerAuthService service = mock(PassengerAuthService.class);
    private final PassengerAuthController controller = new PassengerAuthController(service);

    @Test
    void logoutUsesTrustedAuthEpochAsExpectedEpoch() {
        PassengerLogoutResult result = new PassengerLogoutResult();
        when(service.logout(7L, 9L)).thenReturn(result);

        var response = controller.logout(7L, 9L);

        assertThat(response.getData()).isSameAs(result);
        verify(service).logout(7L, 9L);
    }

    @Test
    void wsTokenBuildsVerifiedContextFromTrustedHeaders() {
        CustomerLoginResponse result = new CustomerLoginResponse();
        PassengerAuthContext context = new PassengerAuthContext(7L, "13800138000", 9L, NORMAL, 1, null);
        when(service.issueWsToken(context)).thenReturn(result);

        var response = controller.wsToken(7L, "13800138000", 9L, "NORMAL", null);

        assertThat(response.getData()).isSameAs(result);
        verify(service).issueWsToken(context);
    }
}
