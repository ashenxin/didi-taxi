package com.sx.order.integration;

import com.sx.order.client.CalculateSettlementClient;
import com.sx.order.client.WalletPaymentClient;
import com.sx.order.dao.*;
import com.sx.order.model.TripOrder;
import com.sx.order.model.dto.*;
import com.sx.order.service.TripOrderSettlementService;
import com.sx.order.service.TripOrderWriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class TripSettlementFlowIntegrationTest {
    @Autowired TripOrderWriteService orderService;
    @Autowired TripOrderSettlementService settlementService;
    @Autowired TripOrderEntityMapper orderMapper;
    @Autowired TripOrderSettlementMapper settlementMapper;
    @Autowired OrderEventEntityMapper eventMapper;
    @Autowired OrderOutboxEventMapper outboxMapper;
    @MockBean CalculateSettlementClient calculateClient;
    @MockBean WalletPaymentClient walletClient;

    @BeforeEach void clean() {
        settlementMapper.delete(null); outboxMapper.delete(null); eventMapper.delete(null); orderMapper.delete(null);
    }

    @Test
    void finishAutoPayFailureManualPaySuccessUnblocksNextBooking() {
        TripOrder order = startedOrder();
        orderMapper.insert(order);
        FinishOrderBody finish = new FinishOrderBody(); finish.setDriverId(80001L);
        orderService.finish(order.getOrderNo(), finish, "flow-finish-key");
        assertThat(settlementMapper.selectList(null)).singleElement()
                .extracting("settlementStatus").isEqualTo("CALCULATING");

        when(calculateClient.calculateFinalFare(any())).thenReturn(
                new FinalFareResult(new BigDecimal("30.00"), "fare-v1", 12_340L, 1_560L));
        when(calculateClient.lockCoupon(any())).thenReturn(new CouponLockResult(
                null, null, null, null, null, null, null, null, null,
                BigDecimal.ZERO.setScale(2), new BigDecimal("30.00")));
        when(walletClient.createPaymentAttempt(any())).thenReturn(
                payment(order.getOrderNo(), "PAY-AUTO-FAILED", "FAILED", "ALIPAY"),
                payment(order.getOrderNo(), "PAY-MANUAL-SUCCESS", "SUCCESS", "WECHAT"));

        settlementService.process(order.getOrderNo());
        assertThat(settlementMapper.selectList(null)).singleElement()
                .extracting("settlementStatus").isEqualTo("PAYMENT_REQUIRED");

        var paid = settlementService.createManualPayment(order.getOrderNo(), 10001L,
                "integration-manual-1", new ManualPaymentCommand("WECHAT"));

        assertThat(paid.getStatus()).isEqualTo("SUCCESS");
        assertThat(settlementMapper.selectList(null)).singleElement().satisfies(row -> {
            assertThat(row.getSettlementStatus()).isEqualTo("PAID");
            assertThat(row.getPaidAmount()).isEqualByComparingTo("30.00");
        });
        assertThat(orderMapper.selectById(order.getId()).getBlocksNewOrder()).isNull();
    }

    private static PaymentAttemptResult payment(String orderNo, String paymentNo, String status, String channel) {
        PaymentAttemptResult result = new PaymentAttemptResult();
        result.setOrderNo(orderNo); result.setPaymentNo(paymentNo); result.setStatus(status);
        result.setPassengerId(10001L); result.setAmount(new BigDecimal("30.00"));
        result.setChannel(channel); result.setOccurredAt(LocalDateTime.now());
        return result;
    }

    private static TripOrder startedOrder() {
        LocalDateTime now = LocalDateTime.now();
        return new TripOrder().setOrderNo("FLOW-ORDER-1").setPassengerId(10001L).setDriverId(80001L)
                .setCompanyId(9L).setProductCode("ECONOMY").setProvinceCode("330000").setCityCode("330100")
                .setOriginAddress("杭州东站").setOriginLat(new BigDecimal("30.2912000"))
                .setOriginLng(new BigDecimal("120.2120000")).setDestAddress("龙翔桥")
                .setDestLat(new BigDecimal("30.2592000")).setDestLng(new BigDecimal("120.1640000")).setStatus(4)
                .setEstimatedAmount(new BigDecimal("30.00")).setFareRuleId(7L)
                .setFareRuleSnapshot("{\"baseFare\":12.00}").setFareCalculationVersion("fare-v1")
                .setPlannedDistanceMeters(12_340L).setPlannedDurationSeconds(1_560L)
                .setDistanceSource("LOCAL_MOCK_ROUTE").setRouteMockVersion("mock-route-v1")
                .setBlocksNewOrder(1).setOfferRound(1).setCreatedAt(now.minusMinutes(30))
                .setStartedAt(now.minusMinutes(20)).setUpdatedAt(now).setIsDeleted(0);
    }
}
