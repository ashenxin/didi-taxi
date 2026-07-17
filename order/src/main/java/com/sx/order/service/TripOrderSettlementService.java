package com.sx.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.order.client.CalculateSettlementClient;
import com.sx.order.dao.TripOrderEntityMapper;
import com.sx.order.dao.TripOrderSettlementMapper;
import com.sx.order.model.TripOrder;
import com.sx.order.model.TripOrderSettlement;
import com.sx.order.model.dto.CouponLockRequest;
import com.sx.order.model.dto.CouponLockResult;
import com.sx.order.model.dto.CouponUseRequest;
import com.sx.order.model.dto.FinalFareRequest;
import com.sx.order.model.dto.FinalFareResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@Slf4j
public class TripOrderSettlementService {

    private static final int STATUS_FINISHED = 5;
    private static final BigDecimal SERVICE_FEE_RATE = new BigDecimal("0.0500");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final TripOrderEntityMapper orderMapper;
    private final TripOrderSettlementMapper settlementMapper;
    private final MockTripMetricsProvider metricsProvider;
    private final CalculateSettlementClient calculateClient;
    private final SettlementAmountValidator amountValidator;
    private final ObjectMapper objectMapper;

    public TripOrderSettlementService(TripOrderEntityMapper orderMapper,
                                      TripOrderSettlementMapper settlementMapper,
                                      MockTripMetricsProvider metricsProvider,
                                      CalculateSettlementClient calculateClient,
                                      SettlementAmountValidator amountValidator,
                                      ObjectMapper objectMapper) {
        this.orderMapper = orderMapper;
        this.settlementMapper = settlementMapper;
        this.metricsProvider = metricsProvider;
        this.calculateClient = calculateClient;
        this.amountValidator = amountValidator;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void process(String orderNo) {
        TripOrder order = getOrder(orderNo);
        TripOrderSettlement settlement = getSettlement(orderNo);
        if (order == null || settlement == null) {
            log.error("结算任务缺少订单或结算记录 orderNo={}", orderNo);
            return;
        }
        if ("PAID".equals(settlement.getSettlementStatus())) {
            return;
        }
        if (Integer.valueOf(1).equals(settlement.getManualActionRequired())) {
            return;
        }
        if (!Integer.valueOf(STATUS_FINISHED).equals(order.getStatus())) {
            markFailure(settlement, "ORDER_NOT_FINISHED", "订单尚未完单", true);
            return;
        }
        if (!hasFrozenPricingInputs(order)) {
            markFailure(settlement, "MISSING_PRICING_SNAPSHOT", "缺少冻结路线指标、计价规则快照或版本", true);
            return;
        }
        freezeMockActualDuration(order);
        order = getOrder(orderNo);
        if (settlement.getFinalAmount() != null) {
            clearRecoverableFailure(settlement);
            return;
        }
        calculateAndFreezeBill(order, settlement);
    }

    private void calculateAndFreezeBill(TripOrder order, TripOrderSettlement settlement) {
        CouponLockResult coupon = null;
        try {
            FinalFareResult fare = calculateClient.calculateFinalFare(new FinalFareRequest(
                    order.getFareRuleSnapshot(),
                    order.getFareCalculationVersion(),
                    order.getPlannedDistanceMeters(),
                    order.getMockActualDurationSeconds()));
            BigDecimal finalAmount = fare == null ? null : fare.finalAmount();
            SettlementAmountValidator.ValidatedAmounts fareOnly =
                    amountValidator.validate(finalAmount, ZERO, finalAmount);

            coupon = calculateClient.lockCoupon(new CouponLockRequest(
                    order.getPassengerId(), order.getOrderNo(), null, fareOnly.finalAmount(),
                    order.getCompanyId(), order.getCityCode(), order.getProductCode(), false));
            BigDecimal discount = coupon == null ? null : coupon.discountAmount();
            BigDecimal payable = coupon == null ? null : coupon.payableAmount();
            SettlementAmountValidator.ValidatedAmounts amounts =
                    amountValidator.validate(fareOnly.finalAmount(), discount, payable);

            if (!persistBill(order, settlement, fare, coupon, amounts)) {
                return;
            }
            if (amounts.payableAmount().compareTo(ZERO) == 0) {
                finalizeZeroPayment(order, settlement, coupon, amounts);
            }
        } catch (IllegalArgumentException ex) {
            if (!isAmountOutOfRange(ex)) {
                throw ex;
            }
            boolean released = releaseCouponCompensation(order, coupon);
            if (released) {
                markFailure(settlement, "AMOUNT_OUT_OF_RANGE", ex.getMessage(), true);
            } else {
                markFailure(settlement, "COUPON_RELEASE_FAILED",
                        ex.getMessage() + "; 优惠券释放失败，需要人工处理", true);
            }
        }
    }

    private boolean persistBill(TripOrder order,
                                TripOrderSettlement settlement,
                                FinalFareResult fare,
                                CouponLockResult coupon,
                                SettlementAmountValidator.ValidatedAmounts amounts) {
        BigDecimal fee = amounts.payableAmount().multiply(SERVICE_FEE_RATE)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal carrierIncome = amounts.payableAmount().subtract(fee).setScale(2, RoundingMode.HALF_UP);
        SettlementAmountValidator.ValidatedDistribution distribution = amountValidator.validateDistribution(
                amounts.payableAmount(), SERVICE_FEE_RATE, fee, carrierIncome);
        LocalDateTime now = LocalDateTime.now();
        int frozen = settlementMapper.update(null, Wrappers.<TripOrderSettlement>lambdaUpdate()
                .set(TripOrderSettlement::getFinalAmount, amounts.finalAmount())
                .set(TripOrderSettlement::getCouponId, coupon.couponId())
                .set(TripOrderSettlement::getCouponTemplateId, coupon.templateId())
                .set(TripOrderSettlement::getCouponCompanyId, coupon.companyId())
                .set(TripOrderSettlement::getCouponCompanyNo, coupon.companyNo())
                .set(TripOrderSettlement::getCouponCompanyNameSnapshot, coupon.companyNameSnapshot())
                .set(TripOrderSettlement::getCouponTeamIdSnapshot, coupon.teamIdSnapshot())
                .set(TripOrderSettlement::getCouponTeamNameSnapshot, coupon.teamNameSnapshot())
                .set(TripOrderSettlement::getCouponType, coupon.couponType())
                .set(TripOrderSettlement::getCouponDiscountAmount, amounts.discountAmount())
                .set(TripOrderSettlement::getCouponRuleSnapshot, coupon.couponRuleSnapshot())
                .set(TripOrderSettlement::getPayableAmount, amounts.payableAmount())
                .set(TripOrderSettlement::getPlatformServiceFeeRate, distribution.platformServiceFeeRate())
                .set(TripOrderSettlement::getPlatformServiceFeeAmount, distribution.platformServiceFeeAmount())
                .set(TripOrderSettlement::getCarrierIncomeAmount, distribution.carrierIncomeAmount())
                .set(TripOrderSettlement::getSettlementSnapshot,
                        settlementSnapshot(fare, amounts, fee, carrierIncome))
                .set(TripOrderSettlement::getFailureCode, null)
                .set(TripOrderSettlement::getFailureSummary, null)
                .set(TripOrderSettlement::getManualActionRequired, 0)
                .set(TripOrderSettlement::getUpdatedAt, now)
                .eq(TripOrderSettlement::getId, settlement.getId())
                .isNull(TripOrderSettlement::getFinalAmount));
        if (frozen != 1) {
            return false;
        }
        int orderFrozen = orderMapper.update(null, Wrappers.<TripOrder>lambdaUpdate()
                .set(TripOrder::getFinalAmount, amounts.finalAmount())
                .set(TripOrder::getUpdatedAt, now)
                .eq(TripOrder::getId, order.getId())
                .isNull(TripOrder::getFinalAmount));
        if (orderFrozen != 1) {
            throw new IllegalStateException("订单主表金额固化失败，结算事务已回滚 orderNo=" + order.getOrderNo());
        }
        return true;
    }

    private void finalizeZeroPayment(TripOrder order,
                                     TripOrderSettlement settlement,
                                     CouponLockResult coupon,
                                     SettlementAmountValidator.ValidatedAmounts amounts) {
        if (coupon.couponId() != null) {
            calculateClient.useCoupon(new CouponUseRequest(
                    order.getPassengerId(), coupon.couponId(), order.getOrderNo(), amounts.discountAmount()));
        }
        LocalDateTime now = LocalDateTime.now();
        int paid = settlementMapper.update(null, Wrappers.<TripOrderSettlement>lambdaUpdate()
                .set(TripOrderSettlement::getPaymentStatus, 2)
                .set(TripOrderSettlement::getPaidAmount, ZERO)
                .set(TripOrderSettlement::getPaidAt, now)
                .set(TripOrderSettlement::getSettlementStatus, "PAID")
                .set(TripOrderSettlement::getSettledAt, now)
                .set(TripOrderSettlement::getUpdatedAt, now)
                .eq(TripOrderSettlement::getId, settlement.getId())
                .eq(TripOrderSettlement::getSettlementStatus, "CALCULATING"));
        if (paid != 1) {
            throw new IllegalStateException("零元订单状态推进失败，结算事务已回滚 orderNo=" + order.getOrderNo());
        }
        int unblocked = orderMapper.update(null, Wrappers.<TripOrder>lambdaUpdate()
                .set(TripOrder::getBlocksNewOrder, null)
                .set(TripOrder::getUpdatedAt, now)
                .eq(TripOrder::getId, order.getId())
                .eq(TripOrder::getBlocksNewOrder, 1));
        if (unblocked != 1) {
            throw new IllegalStateException("零元订单解除下单阻塞失败，结算事务已回滚 orderNo=" + order.getOrderNo());
        }
    }

    private boolean releaseCouponCompensation(TripOrder order, CouponLockResult coupon) {
        if (coupon == null || coupon.couponId() == null) {
            return true;
        }
        try {
            calculateClient.releaseCoupon(new CouponUseRequest(
                    order.getPassengerId(), coupon.couponId(), order.getOrderNo(), coupon.discountAmount()));
            return true;
        } catch (RuntimeException ex) {
            log.error("结算失败后释放优惠券失败，需要人工处理 orderNo={} couponId={}",
                    order.getOrderNo(), coupon.couponId(), ex);
            return false;
        }
    }

    private boolean isAmountOutOfRange(IllegalArgumentException ex) {
        return ex.getMessage() != null && ex.getMessage().contains("AMOUNT_OUT_OF_RANGE");
    }

    private String settlementSnapshot(FinalFareResult fare,
                                      SettlementAmountValidator.ValidatedAmounts amounts,
                                      BigDecimal fee,
                                      BigDecimal carrierIncome) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("fareCalculationVersion", fare.fareCalculationVersion());
        snapshot.put("billingDistanceMeters", fare.billingDistanceMeters());
        snapshot.put("billingDurationSeconds", fare.billingDurationSeconds());
        snapshot.put("finalAmount", amounts.finalAmount());
        snapshot.put("discountAmount", amounts.discountAmount());
        snapshot.put("payableAmount", amounts.payableAmount());
        snapshot.put("platformServiceFeeRate", SERVICE_FEE_RATE);
        snapshot.put("platformServiceFeeAmount", fee);
        snapshot.put("carrierIncomeAmount", carrierIncome);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("结算快照序列化失败", ex);
        }
    }

    private void freezeMockActualDuration(TripOrder order) {
        if (order.getMockActualDurationSeconds() != null) {
            return;
        }
        long generated = metricsProvider.generateDurationSeconds(
                order.getOrderNo(), order.getPlannedDurationSeconds());
        LocalDateTime now = LocalDateTime.now();
        orderMapper.update(null, Wrappers.<TripOrder>lambdaUpdate()
                .set(TripOrder::getMockActualDurationSeconds, generated)
                .set(TripOrder::getDurationSource, "LOCAL_MOCK_TRIP")
                .set(TripOrder::getTripMetricsVersion, metricsProvider.version())
                .set(TripOrder::getUpdatedAt, now)
                .eq(TripOrder::getOrderNo, order.getOrderNo())
                .eq(TripOrder::getIsDeleted, 0)
                .isNull(TripOrder::getMockActualDurationSeconds));
    }

    private boolean hasFrozenPricingInputs(TripOrder order) {
        return order.getPlannedDistanceMeters() != null && order.getPlannedDistanceMeters() >= 0
                && order.getPlannedDurationSeconds() != null && order.getPlannedDurationSeconds() > 0
                && "LOCAL_MOCK_ROUTE".equals(order.getDistanceSource())
                && StringUtils.hasText(order.getRouteMockVersion())
                && StringUtils.hasText(order.getFareRuleSnapshot())
                && StringUtils.hasText(order.getFareCalculationVersion());
    }

    private void clearRecoverableFailure(TripOrderSettlement settlement) {
        if (settlement.getFailureCode() == null && Integer.valueOf(0).equals(settlement.getManualActionRequired())) {
            return;
        }
        settlementMapper.update(null, Wrappers.<TripOrderSettlement>lambdaUpdate()
                .set(TripOrderSettlement::getFailureCode, null)
                .set(TripOrderSettlement::getFailureSummary, null)
                .set(TripOrderSettlement::getManualActionRequired, 0)
                .set(TripOrderSettlement::getUpdatedAt, LocalDateTime.now())
                .eq(TripOrderSettlement::getId, settlement.getId()));
    }

    private void markFailure(TripOrderSettlement settlement, String code, String summary, boolean manual) {
        settlementMapper.update(null, Wrappers.<TripOrderSettlement>lambdaUpdate()
                .set(TripOrderSettlement::getFailureCode, code)
                .set(TripOrderSettlement::getFailureSummary, summary)
                .set(TripOrderSettlement::getManualActionRequired, manual ? 1 : 0)
                .set(TripOrderSettlement::getUpdatedAt, LocalDateTime.now())
                .eq(TripOrderSettlement::getId, settlement.getId())
                .eq(TripOrderSettlement::getSettlementStatus, "CALCULATING"));
    }

    private TripOrder getOrder(String orderNo) {
        return orderMapper.selectOne(Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getOrderNo, orderNo)
                .eq(TripOrder::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private TripOrderSettlement getSettlement(String orderNo) {
        return settlementMapper.selectOne(Wrappers.<TripOrderSettlement>lambdaQuery()
                .eq(TripOrderSettlement::getOrderNo, orderNo)
                .last("LIMIT 1"));
    }
}
