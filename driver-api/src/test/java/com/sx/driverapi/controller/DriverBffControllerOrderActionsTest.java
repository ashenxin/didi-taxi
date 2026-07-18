package com.sx.driverapi.controller;

import com.sx.driverapi.common.exception.BizErrorException;
import com.sx.driverapi.common.exception.GlobalExceptionHandler;
import com.sx.driverapi.model.order.FinishOrderBody;
import com.sx.driverapi.model.ordercore.DriverActionResult;
import com.sx.driverapi.service.DriverBffService;
import com.sx.driverapi.ws.DriverAssignedPushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DriverBffControllerOrderActionsTest {

    private static final String ORDER_NO = "ORDER-1";
    private static final long AUTHED_DRIVER_ID = 80001L;

    private final DriverBffService service = mock(DriverBffService.class);
    private final DriverAssignedPushService assignedPushService = mock(DriverAssignedPushService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new DriverBffController(service, assignedPushService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"accept", "arrive", "start", "finish"})
    void orderActionsRejectForgedDriverIdentity(String action) throws Exception {
        perform(action, "80001", "{\"driverId\":90001}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("禁止操作其他司机数据"));

        verifyNoInteractions(service, assignedPushService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"accept", "arrive", "start", "finish"})
    void orderActionsRequireTrustedDriverIdentity(String action) throws Exception {
        perform(action, null, "{\"driverId\":80001}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("未授权，请重新登录"));

        verifyNoInteractions(service, assignedPushService);
    }

    @Test
    void nonNumericTrustedIdentityReturnsUnauthorized() throws Exception {
        perform("accept", "driver-80001", "{\"driverId\":80001}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(service, assignedPushService);
    }

    @Test
    void acceptUsesAuthenticatedDriverAndRefreshesAssignedOrders() throws Exception {
        when(service.accept(ORDER_NO, AUTHED_DRIVER_ID, "accept-key"))
                .thenReturn(new DriverActionResult(false));

        performWithIdempotencyKey("accept", " accept-key ", "{\"driverId\":80001}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.replayed").value(false));

        verify(service).accept(ORDER_NO, AUTHED_DRIVER_ID, "accept-key");
        verify(assignedPushService).pushAssignedIfChanged(AUTHED_DRIVER_ID, true);
    }

    @Test
    void arriveUsesAuthenticatedDriver() throws Exception {
        when(service.arrive(ORDER_NO, AUTHED_DRIVER_ID, "arrive-key"))
                .thenReturn(new DriverActionResult(true));

        performWithIdempotencyKey("arrive", "arrive-key", "{\"driverId\":80001}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true));

        verify(service).arrive(ORDER_NO, AUTHED_DRIVER_ID, "arrive-key");
        verifyNoInteractions(assignedPushService);
    }

    @Test
    void startUsesAuthenticatedDriver() throws Exception {
        when(service.start(ORDER_NO, AUTHED_DRIVER_ID, "start-key"))
                .thenReturn(new DriverActionResult(false));

        performWithIdempotencyKey("start", "start-key", "{\"driverId\":80001}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(false));

        verify(service).start(ORDER_NO, AUTHED_DRIVER_ID, "start-key");
        verifyNoInteractions(assignedPushService);
    }

    @Test
    void finishUsesAuthenticatedDriver() throws Exception {
        when(service.finish(org.mockito.ArgumentMatchers.eq(ORDER_NO),
                org.mockito.ArgumentMatchers.any(FinishOrderBody.class),
                org.mockito.ArgumentMatchers.eq("finish-key")))
                .thenReturn(new DriverActionResult(false));

        var request = post("/driver/api/v1/orders/{orderNo}/finish", ORDER_NO)
                .header("X-User-Id", " 80001 ")
                .header("Idempotency-Key", "finish-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"driverId\":80001,\"distanceKm\":12.3,\"durationMin\":31,\"finalAmount\":0.01}");
        mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(false));

        ArgumentCaptor<FinishOrderBody> bodyCaptor = ArgumentCaptor.forClass(FinishOrderBody.class);
        verify(service).finish(org.mockito.ArgumentMatchers.eq(ORDER_NO), bodyCaptor.capture(),
                org.mockito.ArgumentMatchers.eq("finish-key"));
        assertEquals(AUTHED_DRIVER_ID, bodyCaptor.getValue().getDriverId());
        verifyNoInteractions(assignedPushService);
    }

    @Test
    void invalidRequestBodyReturnsBadRequestBeforeStateOperation() throws Exception {
        perform("start", "80001", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(service, assignedPushService);
    }

    @Test
    void stateConflictIsReturnedAsHttp409WithoutAssignedOrderPush() throws Exception {
        doThrow(new BizErrorException(409, "订单状态不允许接单"))
                .when(service).accept(ORDER_NO, AUTHED_DRIVER_ID, "accept-conflict-key");

        performWithIdempotencyKey("accept", "accept-conflict-key", "{\"driverId\":80001}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.msg").value("订单状态不允许接单"));

        verify(service).accept(ORDER_NO, AUTHED_DRIVER_ID, "accept-conflict-key");
        verify(assignedPushService, never()).pushAssignedIfChanged(AUTHED_DRIVER_ID, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"accept", "reject", "cancel", "arrive", "start", "finish"})
    void orderWriteActionsRequireIdempotencyKey(String action) throws Exception {
        String body = "{\"driverId\":80001,\"reasonCode\":\"TOO_FAR\"}";

        perform(action, "80001", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("Idempotency-Key不能为空"));

        verifyNoInteractions(service, assignedPushService);
    }

    @Test
    void rejectPassesIdempotencyKeyAndReturnsReplayResult() throws Exception {
        when(service.reject(ORDER_NO, AUTHED_DRIVER_ID, "TOO_FAR", "reject-replay-key"))
                .thenReturn(new DriverActionResult(true));

        performWithIdempotencyKey("reject", "reject-replay-key",
                        "{\"driverId\":80001,\"reasonCode\":\"TOO_FAR\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.replayed").value(true));

        verify(service).reject(ORDER_NO, AUTHED_DRIVER_ID, "TOO_FAR", "reject-replay-key");
        verify(assignedPushService).pushAssignedIfChanged(AUTHED_DRIVER_ID, true);
    }

    @Test
    void cancelPassesTrimmedIdempotencyKey() throws Exception {
        when(service.driverCancelBeforeArrive(ORDER_NO, AUTHED_DRIVER_ID, "VEHICLE_FAULT", "cancel-key"))
                .thenReturn(new DriverActionResult(false));

        performWithIdempotencyKey("cancel", " cancel-key ",
                        "{\"driverId\":80001,\"reasonCode\":\"VEHICLE_FAULT\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(false));

        verify(service).driverCancelBeforeArrive(
                ORDER_NO, AUTHED_DRIVER_ID, "VEHICLE_FAULT", "cancel-key");
    }

    private ResultActions perform(String action, String userId, String body) throws Exception {
        var request = post("/driver/api/v1/orders/{orderNo}/{action}", ORDER_NO, action)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (userId != null) {
            request.header("X-User-Id", userId);
        }
        return mvc.perform(request);
    }

    private ResultActions performWithIdempotencyKey(String action, String key, String body) throws Exception {
        return mvc.perform(post("/driver/api/v1/orders/{orderNo}/{action}", ORDER_NO, action)
                .header("X-User-Id", String.valueOf(AUTHED_DRIVER_ID))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
