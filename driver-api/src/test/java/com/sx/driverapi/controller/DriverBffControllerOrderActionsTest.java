package com.sx.driverapi.controller;

import com.sx.driverapi.common.exception.BizErrorException;
import com.sx.driverapi.common.exception.GlobalExceptionHandler;
import com.sx.driverapi.model.order.FinishOrderBody;
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
        perform("accept", "80001", "{\"driverId\":80001}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(service).accept(ORDER_NO, AUTHED_DRIVER_ID);
        verify(assignedPushService).pushAssignedIfChanged(AUTHED_DRIVER_ID, true);
    }

    @Test
    void arriveUsesAuthenticatedDriver() throws Exception {
        perform("arrive", "80001", "{\"driverId\":80001}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(service).arrive(ORDER_NO, AUTHED_DRIVER_ID);
        verifyNoInteractions(assignedPushService);
    }

    @Test
    void startUsesAuthenticatedDriver() throws Exception {
        perform("start", "80001", "{\"driverId\":80001}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(service).start(ORDER_NO, AUTHED_DRIVER_ID);
        verifyNoInteractions(assignedPushService);
    }

    @Test
    void finishUsesAuthenticatedDriver() throws Exception {
        perform("finish", " 80001 ", "{\"driverId\":80001,\"distanceKm\":12.3,\"durationMin\":31,\"finalAmount\":0.01}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        ArgumentCaptor<FinishOrderBody> bodyCaptor = ArgumentCaptor.forClass(FinishOrderBody.class);
        verify(service).finish(org.mockito.ArgumentMatchers.eq(ORDER_NO), bodyCaptor.capture());
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
                .when(service).accept(ORDER_NO, AUTHED_DRIVER_ID);

        perform("accept", "80001", "{\"driverId\":80001}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.msg").value("订单状态不允许接单"));

        verify(service).accept(ORDER_NO, AUTHED_DRIVER_ID);
        verify(assignedPushService, never()).pushAssignedIfChanged(AUTHED_DRIVER_ID, true);
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
}
