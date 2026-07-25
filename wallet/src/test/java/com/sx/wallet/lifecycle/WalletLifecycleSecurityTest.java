package com.sx.wallet.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.wallet.lifecycle.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletLifecycleSecurityTest {
    @Test
    void internalPathRequiresExactTokenAndRejectsEncodedBypass() throws Exception {
        WalletLifecycleInternalAuthProperties properties = new WalletLifecycleInternalAuthProperties();
        properties.setToken("test-token");
        var filter = new WalletLifecycleInternalAuthFilter(properties, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/internal/account-lifecycle%2Fwallet/fence");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> called.set(true));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(called).isFalse();
    }

    @Test
    void validTokenPassesAndProductionRejectsDevelopmentDefault() throws Exception {
        WalletLifecycleInternalAuthProperties properties = new WalletLifecycleInternalAuthProperties();
        properties.setToken("test-token");
        var filter = new WalletLifecycleInternalAuthFilter(properties, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/internal/account-lifecycle/wallet/fence");
        request.addHeader("X-Internal-Token", "test-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> called.set(true));
        assertThat(called).isTrue();

        properties.setToken("dev-wallet-lifecycle-change-me");
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        var validator = new WalletLifecycleSecurityStartupValidator(
                properties, production);
        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class);

        MockEnvironment local = new MockEnvironment();
        local.setDefaultProfiles("local");
        new WalletLifecycleSecurityStartupValidator(properties, local).afterPropertiesSet();
    }
}
