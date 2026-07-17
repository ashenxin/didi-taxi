package com.sx.passengerapi.controller;

import com.sx.passengerapi.common.exception.GlobalExceptionHandler;
import com.sx.passengerapi.model.settlement.PaymentInvokeResult;
import com.sx.passengerapi.model.settlement.SettlementDetailVO;
import com.sx.passengerapi.service.PassengerSettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PassengerSettlementControllerTest {
    private final PassengerSettlementService service = mock(PassengerSettlementService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PassengerSettlementController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void queryUsesTrustedPassengerIdentityAndReturnsFixedShape() throws Exception {
        when(service.get("ORDER-1", 10001L)).thenReturn(new SettlementDetailVO(
                "PAYMENT_REQUIRED", new BigDecimal("30.00"), new BigDecimal("5.00"),
                new BigDecimal("25.00"), null, "杭州车队", List.of("ALIPAY", "WECHAT"),
                "请选择支付方式完成支付"));

        mvc.perform(get("/app/api/v1/orders/ORDER-1/settlement")
                        .header("X-User-Id", "10001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settlementStatus").value("PAYMENT_REQUIRED"))
                .andExpect(jsonPath("$.data.availableChannels[1]").value("WECHAT"));
        verify(service).get("ORDER-1", 10001L);
    }

    @Test
    void manualPaymentBodyRejectsForgedAmountAndPassengerId() throws Exception {
        mvc.perform(post("/app/api/v1/orders/ORDER-1/payments")
                        .header("X-User-Id", "10001")
                        .header("Idempotency-Key", "manual-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"ALIPAY\",\"amount\":0.01,\"passengerId\":99999}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).pay(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void manualPaymentRequiresKeyAndReturnsMockCashierPayload() throws Exception {
        when(service.pay("ORDER-1", 10001L, "manual-key", "WECHAT"))
                .thenReturn(new PaymentInvokeResult("PAY-1", "PAYING",
                        new PaymentInvokeResult.InvokePayload("MOCK_CASHIER", "/mock-cashier/PAY-1?token=x")));

        mvc.perform(post("/app/api/v1/orders/ORDER-1/payments")
                        .header("X-User-Id", "10001")
                        .header("Idempotency-Key", "manual-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"WECHAT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invokePayload.type").value("MOCK_CASHIER"));

        mvc.perform(post("/app/api/v1/orders/ORDER-1/payments")
                        .header("X-User-Id", "10001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"WECHAT\"}"))
                .andExpect(status().isBadRequest());
    }
}
