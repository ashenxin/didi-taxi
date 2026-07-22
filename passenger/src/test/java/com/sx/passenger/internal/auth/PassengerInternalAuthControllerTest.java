package com.sx.passenger.internal.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.passenger.auth.session.AuthEpochConflictException;
import com.sx.passenger.auth.session.AuthSessionScope;
import com.sx.passenger.auth.session.AuthoritativeAuthState;
import com.sx.passenger.auth.session.PassengerAuthEpochService;
import com.sx.passenger.common.exception.GlobalExceptionHandler;
import com.sx.passenger.internal.security.PassengerInternalAuthFilter;
import com.sx.passenger.internal.security.PassengerInternalAuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PassengerInternalAuthControllerTest {

    private static final String INTERNAL_HEADER = "X-Internal-Service-Token";
    private static final String TOKEN = "test-passenger-internal-secret-32bytes";

    private final PassengerAuthEpochService service = mock(PassengerAuthEpochService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        PassengerInternalAuthProperties properties = new PassengerInternalAuthProperties();
        properties.setToken(TOKEN);
        PassengerInternalAuthFilter filter = new PassengerInternalAuthFilter(properties, new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(new PassengerInternalAuthController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(filter)
                .build();
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
}
