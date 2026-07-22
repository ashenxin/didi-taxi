package com.sx.passengerapi.common.exception;

import feign.FeignException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    @Test
    void staleCoreLogoutConflictRemainsConflict() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        FeignException exception = mock(FeignException.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(exception.status()).thenReturn(409);
        when(request.getRequestURI()).thenReturn("/app/api/v1/auth/logout");

        var response = handler.feignExceptionHandler(exception, request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getCode()).isEqualTo(409);
        assertThat(response.getBody().getMsg()).isEqualTo("认证状态已变化，请刷新后重试");
    }

    @Test
    void unrelatedDownstreamConflictKeepsExistingBadGatewayMapping() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        FeignException exception = mock(FeignException.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(exception.status()).thenReturn(409);
        when(request.getRequestURI()).thenReturn("/app/api/v1/orders");

        var response = handler.feignExceptionHandler(exception, request);

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody().getCode()).isEqualTo(502);
    }
}
