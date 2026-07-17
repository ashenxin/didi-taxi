package com.sx.passengerapi.integration;

import com.sx.passengerapi.client.OrderClient;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.settlement.OrderSettlementRow;
import com.sx.passengerapi.model.settlement.PaymentAttemptRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class PassengerSettlementContractTest {
    @Autowired MockMvc mvc;
    @MockBean OrderClient orderClient;

    @Test
    void realHttpBoundaryReturnsFixedBillAndRejectsClientOwnedAmount() throws Exception {
        OrderSettlementRow bill = new OrderSettlementRow();
        bill.setOrderNo("FLOW-1"); bill.setPassengerId(10001L);
        bill.setFinalAmount(new BigDecimal("30.00"));
        bill.setCouponDiscountAmount(new BigDecimal("5.00"));
        bill.setPayableAmount(new BigDecimal("25.00"));
        bill.setSettlementStatus("PAYMENT_REQUIRED"); bill.setManualActionRequired(0);
        when(orderClient.passengerSettlement("FLOW-1", 10001L)).thenReturn(ResponseVo.success(bill));

        mvc.perform(get("/app/api/v1/orders/FLOW-1/settlement").header("X-User-Id", "10001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalFare").value(30.0))
                .andExpect(jsonPath("$.data.payableAmount").value(25.0))
                .andExpect(jsonPath("$.data.availableChannels[0]").value("ALIPAY"));

        mvc.perform(post("/app/api/v1/orders/FLOW-1/payments")
                        .header("X-User-Id", "10001").header("Idempotency-Key", "flow-key")
                        .contentType("application/json").content("{\"channel\":\"WECHAT\",\"amount\":0.01}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void realHttpBoundaryReturnsMockCashierInvocation() throws Exception {
        PaymentAttemptRow attempt = new PaymentAttemptRow();
        attempt.setPaymentNo("PAY-FLOW-1"); attempt.setStatus("PAYING");
        attempt.setCheckoutUrl("/mock-cashier/PAY-FLOW-1?token=signed");
        when(orderClient.createManualPayment(any(), any(), any(), any()))
                .thenReturn(ResponseVo.success(attempt));

        mvc.perform(post("/app/api/v1/orders/FLOW-1/payments")
                        .header("X-User-Id", "10001").header("Idempotency-Key", "flow-key")
                        .contentType("application/json").content("{\"channel\":\"WECHAT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invokePayload.type").value("MOCK_CASHIER"))
                .andExpect(jsonPath("$.data.invokePayload.checkoutUrl").value(
                        "http://127.0.0.1:8095/mock-cashier/PAY-FLOW-1?token=signed"));
    }
}
