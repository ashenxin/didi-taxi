package com.sx.passenger.internal.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.passenger.auth.session.AuthEpochConflictException;
import com.sx.passenger.auth.session.AuthSessionScope;
import com.sx.passenger.auth.session.AuthoritativeAuthState;
import com.sx.passenger.auth.session.PassengerAuthEpochService;
import com.sx.passenger.app.AppCustomerAuthController;
import com.sx.passenger.app.AppCustomerAuthService;
import com.sx.passenger.common.exception.GlobalExceptionHandler;
import com.sx.passenger.internal.security.PassengerInternalAuthFilter;
import com.sx.passenger.internal.security.PassengerInternalAuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.io.InputStream;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

class PassengerInternalAuthControllerTest {

    private static final String INTERNAL_HEADER = "X-Internal-Service-Token";
    private static final String TOKEN = "test-passenger-internal-secret-32bytes";

    private final PassengerAuthEpochService service = mock(PassengerAuthEpochService.class);
    private final AppCustomerAuthService appCustomerAuthService = mock(AppCustomerAuthService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        PassengerInternalAuthProperties properties = new PassengerInternalAuthProperties();
        properties.setToken(TOKEN);
        PassengerInternalAuthFilter filter = new PassengerInternalAuthFilter(properties, new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(
                        new PassengerInternalAuthController(service),
                        new AppCustomerAuthController(appCustomerAuthService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(filter)
                .build();
    }

    @Test
    void matrixParameterCannotBypassInternalAuthentication() throws Exception {
        when(service.loadState(7L)).thenReturn(new AuthoritativeAuthState(
                7L, 0, "ACTIVE", 9L, null, AuthSessionScope.NORMAL, true));

        mvc.perform(get("/api/v1/internal;probe/auth-state/7"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(service);
    }

    @Test
    void matrixParameterCannotBypassAppAuthentication() throws Exception {
        mvc.perform(post("/api/v1/app;probe/auth/login-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800138000\",\"password\":\"secret\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(appCustomerAuthService);
    }

    @Test
    void exactTokenAllowsMatrixInternalPathToReachController() throws Exception {
        when(service.loadState(7L)).thenReturn(new AuthoritativeAuthState(
                7L, 0, "ACTIVE", 9L, null, AuthSessionScope.NORMAL, true));

        mvc.perform(get("/api/v1/internal;probe/auth-state/7").header(INTERNAL_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(7))
                .andExpect(jsonPath("$.data.authEpoch").value(9))
                .andExpect(jsonPath("$.data.allowedScope").value("NORMAL"));

        verify(service).loadState(7L);
    }

    @Test
    void encodedInternalPrefixCannotBypassAuthentication() throws Exception {
        when(service.loadState(7L)).thenReturn(new AuthoritativeAuthState(
                7L, 0, "ACTIVE", 9L, null, AuthSessionScope.NORMAL, true));

        mvc.perform(get(URI.create("/api/v1/%69nternal/auth-state/7")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(service);
    }

    @Test
    void encodedAppPrefixCannotBypassAuthentication() throws Exception {
        mvc.perform(post(URI.create("/api/v1/%61pp/auth/login-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800138000\",\"password\":\"secret\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(appCustomerAuthService);
    }

    @Test
    void exactTokenAllowsEncodedInternalPathToReachController() throws Exception {
        when(service.loadState(7L)).thenReturn(new AuthoritativeAuthState(
                7L, 0, "ACTIVE", 9L, null, AuthSessionScope.NORMAL, true));

        mvc.perform(get(URI.create("/api/v1/%69nternal/auth-state/7")).header(INTERNAL_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(7))
                .andExpect(jsonPath("$.data.authEpoch").value(9))
                .andExpect(jsonPath("$.data.allowedScope").value("NORMAL"));

        verify(service).loadState(7L);
    }

    @Test
    void returnsCompleteAuthoritativeState() throws Exception {
        when(service.loadState(7L)).thenReturn(new AuthoritativeAuthState(
                7L, 0, "CANCELLING", 9L, "op-1", AuthSessionScope.LIFECYCLE_RESTRICTED, true));

        mvc.perform(get("/api/v1/internal/auth-state/7").header(INTERNAL_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.customerId").value(7))
                .andExpect(jsonPath("$.data.businessStatus").value(0))
                .andExpect(jsonPath("$.data.lifecycleStatus").value("CANCELLING"))
                .andExpect(jsonPath("$.data.authEpoch").value(9))
                .andExpect(jsonPath("$.data.currentLifecycleOperationNo").value("op-1"))
                .andExpect(jsonPath("$.data.allowedScope").value("LIFECYCLE_RESTRICTED"))
                .andExpect(jsonPath("$.data.allowed").value(true));

        verify(service).loadState(7L);
    }

    @Test
    void returnsDisallowedAuthoritativeStateWithOkStatus() throws Exception {
        when(service.loadState(404L)).thenReturn(new AuthoritativeAuthState(
                404L, null, null, 0L, null, null, false));

        mvc.perform(get("/api/v1/internal/auth-state/404").header(INTERNAL_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authEpoch").value(0))
                .andExpect(jsonPath("$.data.allowedScope").doesNotExist())
                .andExpect(jsonPath("$.data.allowed").value(false));
    }

    @Test
    void logoutReturnsNewAuthoritativeEpoch() throws Exception {
        when(service.logout(7L, 8L)).thenReturn(9L);

        mvc.perform(post("/api/v1/internal/auth-state/logout")
                        .header(INTERNAL_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":7,\"expectedAuthEpoch\":8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(7))
                .andExpect(jsonPath("$.data.authEpoch").value(9));

        verify(service).logout(7L, 8L);
    }

    @Test
    void staleLogoutReturnsConflict() throws Exception {
        when(service.logout(7L, 8L)).thenThrow(new AuthEpochConflictException());

        mvc.perform(post("/api/v1/internal/auth-state/logout")
                        .header(INTERNAL_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":7,\"expectedAuthEpoch\":8}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void infrastructureFailureReturnsServiceUnavailable() throws Exception {
        when(service.loadState(7L)).thenThrow(new DataAccessResourceFailureException("database unavailable"));

        mvc.perform(get("/api/v1/internal/auth-state/7").header(INTERNAL_HEADER, TOKEN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.msg").value("服务暂不可用，请稍后重试"));
    }

    @Test
    void controllerResponsesConformToSharedAuthStateContractFixture() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream input = getClass().getResourceAsStream("/contracts/passenger-auth-state-v1.json")) {
            assertThat(input).as("共享认证状态契约必须位于 classpath:/contracts").isNotNull();
            var cases = mapper.readTree(input).path("cases");
            assertThat(cases).hasSize(3);
            for (var contractCase : cases) {
                var expected = contractCase.path("response");
                long customerId = expected.path("customerId").asLong();
                AuthSessionScope scope = expected.path("allowedScope").isNull()
                        ? null : AuthSessionScope.valueOf(expected.path("allowedScope").asText());
                Integer businessStatus = expected.path("businessStatus").isNull()
                        ? null : expected.path("businessStatus").asInt();
                String lifecycleStatus = expected.path("lifecycleStatus").isNull()
                        ? null : expected.path("lifecycleStatus").asText();
                String operationNo = expected.path("currentLifecycleOperationNo").isNull()
                        ? null : expected.path("currentLifecycleOperationNo").asText();
                when(service.loadState(customerId)).thenReturn(new AuthoritativeAuthState(
                        customerId, businessStatus, lifecycleStatus, expected.path("authEpoch").asLong(),
                        operationNo, scope, expected.path("allowed").asBoolean()));

                String response = mvc.perform(get("/api/v1/internal/auth-state/{customerId}", customerId)
                                .header(INTERNAL_HEADER, TOKEN))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                assertThat(mapper.readTree(response).path("data")).isEqualTo(expected);
            }
        }
    }
}
