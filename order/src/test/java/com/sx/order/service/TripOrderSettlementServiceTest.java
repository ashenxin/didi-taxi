package com.sx.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.order.dao.TripOrderEntityMapper;
import com.sx.order.dao.TripOrderSettlementMapper;
import com.sx.order.client.CalculateSettlementClient;
import com.sx.order.model.TripOrder;
import com.sx.order.model.TripOrderSettlement;
import com.sx.order.model.dto.CouponLockResult;
import com.sx.order.model.dto.FinalFareResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class TripOrderSettlementServiceTest {

    @Autowired
    private TripOrderSettlementService service;
    @Autowired
    private TripOrderEntityMapper orderMapper;
    @Autowired
    private TripOrderSettlementMapper settlementMapper;
    @MockBean
    private CalculateSettlementClient calculateClient;

    @BeforeEach
    void clean() {
        settlementMapper.delete(null);
        orderMapper.delete(null);
        reset(calculateClient);
        when(calculateClient.calculateFinalFare(any())).thenReturn(
                new FinalFareResult(new BigDecimal("30.00"), "fare-v1", 12_340L, 1_560L));
        when(calculateClient.lockCoupon(any())).thenReturn(noCoupon("30.00"));
    }

    @Test
    void firstProcessingFreezesMockDurationAndReplayKeepsOriginalValue() {
        TripOrder order = finishedOrder();
        orderMapper.insert(order);
        settlementMapper.insert(calculatingSettlement());

        service.process(order.getOrderNo());
        TripOrder first = order(order.getOrderNo());
        assertThat(first.getMockActualDurationSeconds()).isEqualTo(1_560L);
        assertThat(first.getDurationSource()).isEqualTo("LOCAL_MOCK_TRIP");
        assertThat(first.getTripMetricsVersion()).isEqualTo("mock-trip-v1");

        first.setStartedAt(first.getStartedAt().minusHours(3));
        first.setFinishedAt(first.getFinishedAt().plusHours(5));
        orderMapper.updateById(first);
        service.process(order.getOrderNo());

        assertThat(order(order.getOrderNo()).getMockActualDurationSeconds()).isEqualTo(1_560L);
    }

    @Test
    void missingFrozenPricingSnapshotStopsBeforeGeneratingMetrics() {
        TripOrder order = finishedOrder();
        order.setFareRuleSnapshot(null);
        orderMapper.insert(order);
        settlementMapper.insert(calculatingSettlement());

        service.process(order.getOrderNo());

        TripOrderSettlement settlement = settlement(order.getOrderNo());
        assertThat(settlement.getSettlementStatus()).isEqualTo("CALCULATING");
        assertThat(settlement.getFailureCode()).isEqualTo("MISSING_PRICING_SNAPSHOT");
        assertThat(settlement.getManualActionRequired()).isEqualTo(1);
        assertThat(order(order.getOrderNo()).getMockActualDurationSeconds()).isNull();
    }

    @ParameterizedTest
    @MethodSource("invalidFinalAmounts")
    void invalidFinalAmountStaysCalculatingAndDoesNotLockCoupon(BigDecimal finalAmount) {
        TripOrder order = finishedOrder();
        orderMapper.insert(order);
        settlementMapper.insert(calculatingSettlement());
        when(calculateClient.calculateFinalFare(any())).thenReturn(
                new FinalFareResult(finalAmount, "fare-v1", 12_340L, 1_560L));

        service.process(order.getOrderNo());

        TripOrderSettlement settlement = settlement(order.getOrderNo());
        assertThat(settlement.getSettlementStatus()).isEqualTo("CALCULATING");
        assertThat(settlement.getFailureCode()).isEqualTo("AMOUNT_OUT_OF_RANGE");
        assertThat(settlement.getManualActionRequired()).isEqualTo(1);
        assertThat(settlement.getFinalAmount()).isNull();
        verify(calculateClient, never()).lockCoupon(any());
        verify(calculateClient, never()).useCoupon(any());
    }

    @ParameterizedTest
    @MethodSource("invalidCouponAmounts")
    void invalidLockedCouponAmountsReleaseCouponOnce(String discountAmount, String payableAmount) {
        TripOrder order = finishedOrder();
        orderMapper.insert(order);
        settlementMapper.insert(calculatingSettlement());
        when(calculateClient.lockCoupon(any())).thenReturn(coupon(discountAmount, payableAmount));

        service.process(order.getOrderNo());

        TripOrderSettlement settlement = settlement(order.getOrderNo());
        assertThat(settlement.getSettlementStatus()).isEqualTo("CALCULATING");
        assertThat(settlement.getFailureCode()).isEqualTo("AMOUNT_OUT_OF_RANGE");
        assertThat(settlement.getManualActionRequired()).isEqualTo(1);
        verify(calculateClient, times(1)).releaseCoupon(any());
        verify(calculateClient, never()).useCoupon(any());
    }

    @Test
    void zeroPayableUsesCouponOnceMarksPaidAndUnblocksPassenger() {
        TripOrder order = finishedOrder();
        orderMapper.insert(order);
        settlementMapper.insert(calculatingSettlement());
        when(calculateClient.calculateFinalFare(any())).thenReturn(
                new FinalFareResult(new BigDecimal("30.005"), "fare-v1", 12_340L, 1_560L));
        when(calculateClient.lockCoupon(any())).thenReturn(coupon("30.006", "0.004"));

        service.process(order.getOrderNo());
        service.process(order.getOrderNo());

        TripOrderSettlement settlement = settlement(order.getOrderNo());
        assertThat(settlement.getFinalAmount()).isEqualByComparingTo("30.01");
        assertThat(settlement.getCouponDiscountAmount()).isEqualByComparingTo("30.01");
        assertThat(settlement.getPayableAmount()).isZero();
        assertThat(settlement.getPlatformServiceFeeAmount()).isZero();
        assertThat(settlement.getCarrierIncomeAmount()).isZero();
        assertThat(settlement.getSettlementStatus()).isEqualTo("PAID");
        assertThat(settlement.getPaymentNo()).isNull();
        assertThat(settlement.getPaidAmount()).isZero();
        assertThat(order(order.getOrderNo()).getFinalAmount()).isEqualByComparingTo("30.01");
        assertThat(order(order.getOrderNo()).getBlocksNewOrder()).isNull();
        verify(calculateClient, times(1)).useCoupon(any());
    }

    @Test
    void exactUpperBoundaryPersistsFeeAndCarrierIncome() {
        TripOrder order = finishedOrder();
        orderMapper.insert(order);
        settlementMapper.insert(calculatingSettlement());
        when(calculateClient.calculateFinalFare(any())).thenReturn(
                new FinalFareResult(new BigDecimal("10000.00"), "fare-v1", 12_340L, 1_560L));
        when(calculateClient.lockCoupon(any())).thenReturn(noCoupon("10000.00"));

        service.process(order.getOrderNo());

        TripOrderSettlement settlement = settlement(order.getOrderNo());
        assertThat(settlement.getFinalAmount()).isEqualByComparingTo("10000.00");
        assertThat(settlement.getPlatformServiceFeeRate()).isEqualByComparingTo("0.0500");
        assertThat(settlement.getPlatformServiceFeeAmount()).isEqualByComparingTo("500.00");
        assertThat(settlement.getCarrierIncomeAmount()).isEqualByComparingTo("9500.00");
        assertThat(settlement.getSettlementStatus()).isEqualTo("CALCULATING");
    }

    @Test
    void existingDifferentOrderFinalAmountRollsBackSettlementSnapshot() {
        TripOrder order = finishedOrder().setFinalAmount(new BigDecimal("29.00"));
        orderMapper.insert(order);
        settlementMapper.insert(calculatingSettlement());

        assertThatThrownBy(() -> service.process(order.getOrderNo()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("订单主表金额固化失败");

        assertThat(settlement(order.getOrderNo()).getFinalAmount()).isNull();
        assertThat(order(order.getOrderNo()).getFinalAmount()).isEqualByComparingTo("29.00");
    }

    @Test
    void couponReleaseFailureIsPersistedForManualHandling() {
        TripOrder order = finishedOrder();
        orderMapper.insert(order);
        settlementMapper.insert(calculatingSettlement());
        when(calculateClient.lockCoupon(any())).thenReturn(coupon("31.00", "-1.00"));
        org.mockito.Mockito.doThrow(new IllegalStateException("calculate unavailable"))
                .when(calculateClient).releaseCoupon(any());

        service.process(order.getOrderNo());

        TripOrderSettlement settlement = settlement(order.getOrderNo());
        assertThat(settlement.getFailureCode()).isEqualTo("COUPON_RELEASE_FAILED");
        assertThat(settlement.getFailureSummary()).contains("AMOUNT_OUT_OF_RANGE");
        assertThat(settlement.getManualActionRequired()).isEqualTo(1);
    }

    @Test
    void manualFailureDoesNotAutomaticallyRetryCalculation() {
        TripOrder order = finishedOrder();
        orderMapper.insert(order);
        TripOrderSettlement settlement = calculatingSettlement()
                .setFailureCode("AMOUNT_OUT_OF_RANGE")
                .setManualActionRequired(1);
        settlementMapper.insert(settlement);

        service.process(order.getOrderNo());

        verify(calculateClient, never()).calculateFinalFare(any());
    }

    @Test
    void concurrentPositiveBillLoserDoesNotReleaseWinningCoupon() throws Exception {
        TripOrder order = settlementReadyOrder();
        orderMapper.insert(order);
        settlementMapper.insert(calculatingSettlement());
        runConcurrentSettlement(order.getOrderNo(), coupon("5.00", "25.00"));

        TripOrderSettlement settlement = settlement(order.getOrderNo());
        assertThat(settlement.getCouponId()).isEqualTo(88L);
        assertThat(settlement.getPayableAmount()).isEqualByComparingTo("25.00");
        verify(calculateClient, never()).releaseCoupon(any());
    }

    @Test
    void concurrentZeroBillUsesWinningCouponOnlyOnce() throws Exception {
        TripOrder order = settlementReadyOrder();
        orderMapper.insert(order);
        settlementMapper.insert(calculatingSettlement());
        runConcurrentSettlement(order.getOrderNo(), coupon("30.00", "0.00"));

        assertThat(settlement(order.getOrderNo()).getSettlementStatus()).isEqualTo("PAID");
        assertThat(order(order.getOrderNo()).getBlocksNewOrder()).isNull();
        verify(calculateClient, times(1)).useCoupon(any());
        verify(calculateClient, never()).releaseCoupon(any());
    }

    private void runConcurrentSettlement(String orderNo, CouponLockResult result) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        when(calculateClient.lockCoupon(any())).thenAnswer(invocation -> {
            barrier.await(5, TimeUnit.SECONDS);
            return result;
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> service.process(orderNo));
            Future<?> second = executor.submit(() -> service.process(orderNo));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private static TripOrder settlementReadyOrder() {
        return finishedOrder()
                .setMockActualDurationSeconds(1_560L)
                .setDurationSource("LOCAL_MOCK_TRIP")
                .setTripMetricsVersion("mock-trip-v1");
    }

    private static Stream<Arguments> invalidFinalAmounts() {
        return Stream.of(
                Arguments.of((BigDecimal) null),
                Arguments.of(BigDecimal.ZERO),
                Arguments.of(new BigDecimal("-0.01")),
                Arguments.of(new BigDecimal("10000.01")));
    }

    private static Stream<Arguments> invalidCouponAmounts() {
        return Stream.of(
                Arguments.of("-0.01", "30.01"),
                Arguments.of("31.00", "-1.00"),
                Arguments.of("5.00", "24.99"));
    }

    private static CouponLockResult noCoupon(String payableAmount) {
        return new CouponLockResult(null, null, null, null, null, null, null, null, null,
                BigDecimal.ZERO.setScale(2), new BigDecimal(payableAmount));
    }

    private static CouponLockResult coupon(String discountAmount, String payableAmount) {
        return new CouponLockResult(88L, 18L, 9L, "C009", "杭州车队", "TEAM-9", "一队",
                "AMOUNT_OFF", "{\"discountAmount\":30}", new BigDecimal(discountAmount),
                new BigDecimal(payableAmount));
    }

    private TripOrder order(String orderNo) {
        return orderMapper.selectOne(Wrappers.<TripOrder>lambdaQuery().eq(TripOrder::getOrderNo, orderNo));
    }

    private TripOrderSettlement settlement(String orderNo) {
        return settlementMapper.selectOne(Wrappers.<TripOrderSettlement>lambdaQuery()
                .eq(TripOrderSettlement::getOrderNo, orderNo));
    }

    private static TripOrderSettlement calculatingSettlement() {
        LocalDateTime now = LocalDateTime.now();
        return new TripOrderSettlement()
                .setOrderNo("T202607170010")
                .setPassengerId(10001L)
                .setEstimatedAmount(new BigDecimal("35.00"))
                .setCouponDiscountAmount(BigDecimal.ZERO.setScale(2))
                .setPayableAmount(BigDecimal.ZERO.setScale(2))
                .setPaymentStatus(0)
                .setSettlementStatus("CALCULATING")
                .setManualActionRequired(0)
                .setVersion(0)
                .setCreatedAt(now)
                .setUpdatedAt(now);
    }

    private static TripOrder finishedOrder() {
        LocalDateTime now = LocalDateTime.now();
        return new TripOrder()
                .setOrderNo("T202607170010")
                .setPassengerId(10001L)
                .setDriverId(80001L)
                .setCompanyId(9L)
                .setProductCode("ECONOMY")
                .setProvinceCode("330000")
                .setCityCode("330100")
                .setOriginAddress("杭州东站")
                .setOriginLat(new BigDecimal("30.2912000"))
                .setOriginLng(new BigDecimal("120.2120000"))
                .setDestAddress("龙翔桥")
                .setDestLat(new BigDecimal("30.2592000"))
                .setDestLng(new BigDecimal("120.1640000"))
                .setStatus(5)
                .setEstimatedAmount(new BigDecimal("35.00"))
                .setFareRuleId(7L)
                .setFareRuleSnapshot("{\"ruleId\":7,\"baseFare\":12.00}")
                .setFareCalculationVersion("fare-v1")
                .setPlannedDistanceMeters(12_340L)
                .setPlannedDurationSeconds(1_560L)
                .setDistanceSource("LOCAL_MOCK_ROUTE")
                .setRouteMockVersion("mock-route-v1")
                .setBlocksNewOrder(1)
                .setOfferRound(1)
                .setStartedAt(now.minusMinutes(30))
                .setFinishedAt(now)
                .setCreatedAt(now.minusHours(1))
                .setUpdatedAt(now)
                .setIsDeleted(0);
    }
}
