package com.sx.wallet.controller;

import com.sx.wallet.config.MockPaymentProperties;
import com.sx.wallet.model.dto.PaymentResult;
import com.sx.wallet.service.PaymentAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class MockCashierControllerTest {

    private final PaymentAttemptService service = mock(PaymentAttemptService.class);
    private final MockPaymentProperties properties = new MockPaymentProperties();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        mvc = standaloneSetup(new MockCashierController(service, properties)).build();
    }

    @Test
    void cashierRendersMaskedReadOnlyPaymentData() throws Exception {
        when(service.getMockCashier("PAY-1", "valid-token")).thenReturn(result("PAYING"));

        mvc.perform(get("/mock-cashier/PAY-1").param("token", "valid-token"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("OR*****34")))
                .andExpect(content().string(containsString("ALIPAY")))
                .andExpect(content().string(containsString("30.00")))
                .andExpect(content().string(not(containsString("name=\"amount\""))));
    }

    @Test
    void invalidAndExpiredTokensUse404And410() throws Exception {
        when(service.getMockCashier("PAY-WRONG", "wrong"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "收银台不存在"));
        when(service.getMockCashier("PAY-OLD", "expired"))
                .thenThrow(new ResponseStatusException(HttpStatus.GONE, "收银台token已过期"));

        mvc.perform(get("/mock-cashier/PAY-WRONG").param("token", "wrong"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/mock-cashier/PAY-OLD").param("token", "expired"))
                .andExpect(status().isGone());
    }

    @Test
    void internalEndpointsQueryAndResolveOriginalAttempt() throws Exception {
        when(service.getPaymentAttempt("PAY-1")).thenReturn(result("CONFIRMING"));
        when(service.resolveMockPayment("PAY-1", "valid-token", "SUCCESS"))
                .thenReturn(result("SUCCESS"));

        mvc.perform(get("/internal/wallet/payment-attempts/PAY-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentNo").value("PAY-1"))
                .andExpect(jsonPath("$.data.status").value("CONFIRMING"));
        mvc.perform(post("/internal/wallet/payment-attempts/PAY-1/mock-resolve")
                        .contentType("application/json")
                        .content("{\"token\":\"valid-token\",\"status\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        verify(service).getPaymentAttempt("PAY-1");
        verify(service).resolveMockPayment("PAY-1", "valid-token", "SUCCESS");
    }

    @Test
    void terminalConflictUses409AndDisabledMockIsHidden() throws Exception {
        when(service.resolveMockPayment("PAY-1", "valid-token", "FAILED"))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "支付尝试终态冲突"));

        mvc.perform(post("/internal/wallet/payment-attempts/PAY-1/mock-resolve")
                        .contentType("application/json")
                        .content("{\"token\":\"valid-token\",\"status\":\"FAILED\"}"))
                .andExpect(status().isConflict());

        properties.setEnabled(false);
        mvc.perform(get("/mock-cashier/PAY-1").param("token", "valid-token"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/internal/wallet/payment-attempts/PAY-1/mock-resolve")
                        .contentType("application/json")
                        .content("{\"token\":\"valid-token\",\"status\":\"SUCCESS\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidResolutionStatusUses400() throws Exception {
        when(service.resolveMockPayment("PAY-1", "valid-token", "UNKNOWN"))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法mock结果"));

        mvc.perform(post("/internal/wallet/payment-attempts/PAY-1/mock-resolve")
                        .contentType("application/json")
                        .content("{\"token\":\"valid-token\",\"status\":\"UNKNOWN\"}"))
                .andExpect(status().isBadRequest());
    }

    private static PaymentResult result(String status) {
        PaymentResult result = new PaymentResult();
        result.setPaymentNo("PAY-1");
        result.setOrderNo("ORDER1234");
        result.setStatus(status);
        result.setChannel("ALIPAY");
        result.setAttemptNo(1);
        result.setAmount(new BigDecimal("30.00"));
        return result;
    }
}
