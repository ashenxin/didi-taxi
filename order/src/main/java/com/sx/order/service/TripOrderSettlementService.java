package com.sx.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.order.client.CalculateSettlementClient;
import com.sx.order.client.WalletPaymentClient;
import com.sx.order.dao.OrderOutboxEventMapper;
import com.sx.order.dao.TripOrderEntityMapper;
import com.sx.order.dao.TripOrderSettlementMapper;
import com.sx.order.model.TripOrder;
import com.sx.order.model.TripOrderSettlement;
import com.sx.order.model.OrderOutboxEvent;
import com.sx.order.model.dto.CouponLockRequest;
import com.sx.order.model.dto.CouponLockResult;
import com.sx.order.model.dto.CouponUseRequest;
import com.sx.order.model.dto.FinalFareRequest;
import com.sx.order.model.dto.FinalFareResult;
import com.sx.order.model.dto.PaymentAttemptRequest;
import com.sx.order.model.dto.PaymentAttemptResult;
import com.sx.order.model.dto.PaymentResultNotification;
import com.sx.order.model.dto.ManualPaymentCommand;
import com.sx.order.model.dto.SettlementSummaryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;

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
    private final WalletPaymentClient walletClient;
    private final OrderOutboxEventMapper outboxMapper;
    private final SettlementAmountValidator amountValidator;
    private final ObjectMapper objectMapper;

    public TripOrderSettlementService(TripOrderEntityMapper orderMapper,
                                      TripOrderSettlementMapper settlementMapper,
                                      MockTripMetricsProvider metricsProvider,
                                      CalculateSettlementClient calculateClient,
                                      WalletPaymentClient walletClient,
                                      OrderOutboxEventMapper outboxMapper,
                                      SettlementAmountValidator amountValidator,
                                      ObjectMapper objectMapper) {
        this.orderMapper = orderMapper;
        this.settlementMapper = settlementMapper;
        this.metricsProvider = metricsProvider;
        this.calculateClient = calculateClient;
        this.walletClient = walletClient;
        this.outboxMapper = outboxMapper;
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
            if (settlement.getPayableAmount() != null
                    && settlement.getPayableAmount().compareTo(ZERO) > 0
                    && "CALCULATING".equals(settlement.getSettlementStatus())) {
                startAutoPay(order, settlement.getPayableAmount());
            }
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
            } else {
                startAutoPay(order, amounts.payableAmount());
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

    private void startAutoPay(TripOrder order, BigDecimal payableAmount) {
        PaymentAttemptResult result;
        try {
            result = walletClient.createPaymentAttempt(new PaymentAttemptRequest(
                    order.getOrderNo(), order.getPassengerId(), payableAmount, "AUTO_PAY", null,
                    "AUTO_PAY:" + order.getOrderNo()));
        } catch (WalletPaymentClient.NoDefaultAgreementException ex) {
            moveToPaymentRequired(order.getOrderNo(), null, "NO_DEFAULT_AGREEMENT");
            return;
        } catch (RuntimeException ex) {
            TripOrderSettlement settlement = getSettlement(order.getOrderNo());
            markFailure(settlement, "AUTO_PAY_REQUEST_FAILED", ex.getMessage(), false);
            log.warn("免密支付请求失败，保留冻结账单等待恢复 orderNo={} err={}",
                    order.getOrderNo(), ex.toString());
            return;
        }
        if (result == null
                || !Objects.equals(order.getOrderNo(), result.getOrderNo())
                || !Objects.equals(order.getPassengerId(), result.getPassengerId())
                || result.getAmount() == null || payableAmount.compareTo(result.getAmount()) != 0) {
            TripOrderSettlement settlement = getSettlement(order.getOrderNo());
            markFailure(settlement, "AUTO_PAY_RESULT_MISMATCH", "钱包创建结果与支付请求不一致", false);
            return;
        }
        // 本地核券、解锁和出站事件失败必须让事务回滚，不能被当作钱包请求失败吞掉。
        applyPaymentResult(result);
    }

    @Transactional
    public String handlePaymentResult(PaymentResultNotification notification) {
        if (notification == null || notification.paymentNo() == null || notification.paymentNo().isBlank()) {
            throw new IllegalArgumentException("支付结果通知缺少paymentNo");
        }
        PaymentAttemptResult authoritative = walletClient.getPaymentAttempt(notification.paymentNo());
        if (!samePayment(notification, authoritative)) {
            throw new IllegalArgumentException("支付结果通知与钱包原交易不一致");
        }
        return applyPaymentResult(authoritative);
    }

    @Transactional
    public String recoverPayment(String orderNo) {
        TripOrderSettlement settlement = getSettlement(orderNo);
        if (settlement == null || !"PAY_CONFIRMING".equals(settlement.getSettlementStatus())) {
            return "SKIPPED";
        }
        String paymentNo = settlement.getPaymentNo() == null
                ? settlement.getActivePaymentNo() : settlement.getPaymentNo();
        if (paymentNo == null || paymentNo.isBlank()) {
            log.error("PAY_CONFIRMING缺少原支付尝试号，不能创建第二笔支付 orderNo={}", orderNo);
            return "MISSING_PAYMENT_NO";
        }
        PaymentAttemptResult result = walletClient.getPaymentAttempt(paymentNo);
        boolean matches = result != null
                && Objects.equals(paymentNo, result.getPaymentNo())
                && Objects.equals(orderNo, result.getOrderNo())
                && Objects.equals(settlement.getPassengerId(), result.getPassengerId())
                && settlement.getPayableAmount() != null && result.getAmount() != null
                && settlement.getPayableAmount().compareTo(result.getAmount()) == 0;
        if (!matches) {
            log.error("钱包原交易与待恢复结算不一致，保持PAY_CONFIRMING orderNo={} paymentNo={}",
                    orderNo, paymentNo);
            return "PAYMENT_QUERY_MISMATCH";
        }
        return applyPaymentResult(result);
    }

    public TripOrderSettlement getPassengerSettlement(String orderNo, Long passengerId) {
        TripOrderSettlement settlement = getSettlement(orderNo);
        if (settlement == null) {
            throw new IllegalArgumentException("订单结算记录不存在");
        }
        if (!Objects.equals(passengerId, settlement.getPassengerId())) {
            throw new SecurityException("无权查看该订单结算");
        }
        return settlement;
    }

    public List<SettlementSummaryResult> getSettlementSummaries(List<String> orderNos) {
        if (orderNos == null || orderNos.isEmpty()) {
            return List.of();
        }
        return settlementMapper.selectList(Wrappers.<TripOrderSettlement>lambdaQuery()
                        .in(TripOrderSettlement::getOrderNo, orderNos)).stream()
                .map(row -> new SettlementSummaryResult(row.getOrderNo(), row.getSettlementStatus(),
                        row.getFinalAmount(), row.getPayableAmount(), row.getPaidAmount()))
                .toList();
    }

    @Transactional
    public PaymentAttemptResult createManualPayment(String orderNo, Long passengerId,
                                                    String idempotencyKey,
                                                    ManualPaymentCommand command) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new IllegalArgumentException("Idempotency-Key不能为空");
        }
        if (command == null || !StringUtils.hasText(command.channel())) {
            throw new IllegalArgumentException("channel不能为空");
        }
        TripOrderSettlement settlement = getSettlementForUpdate(orderNo);
        if (settlement == null) {
            throw new IllegalArgumentException("订单结算记录不存在");
        }
        if (!Objects.equals(passengerId, settlement.getPassengerId())) {
            throw new SecurityException("无权支付该订单");
        }
        if ("PAID".equals(settlement.getSettlementStatus())) {
            PaymentAttemptResult paid = settlement.getPaymentNo() == null
                    ? null : walletClient.getPaymentAttempt(settlement.getPaymentNo());
            if (paid != null) {
                return paid;
            }
            throw new IllegalStateException("订单已支付但缺少支付记录");
        }
        if (settlement.getActivePaymentNo() != null) {
            PaymentAttemptResult active = walletClient.getPaymentAttempt(settlement.getActivePaymentNo());
            validatePaymentIdentity(settlement, active);
            if (!Objects.equals(orderNo, active.getOrderNo())
                    || !Objects.equals(settlement.getActivePaymentNo(), active.getPaymentNo())) {
                throw new IllegalArgumentException("钱包活动交易与订单不一致");
            }
            if ("SUCCESS".equals(active.getStatus())) {
                applyPaymentResult(active);
                return active;
            }
            if ("FAILED".equals(active.getStatus()) || "CANCELLED".equals(active.getStatus())) {
                applyPaymentResult(active);
                settlement = getSettlementForUpdate(orderNo);
                if (settlement == null) {
                    throw new IllegalStateException("主动支付状态恢复失败");
                }
            }
        }
        boolean paymentRequired = "PAYMENT_REQUIRED".equals(settlement.getSettlementStatus());
        boolean confirmingReplay = "PAY_CONFIRMING".equals(settlement.getSettlementStatus())
                && settlement.getActivePaymentNo() != null;
        if ((!paymentRequired && !confirmingReplay)
                || settlement.getPayableAmount() == null
                || settlement.getPayableAmount().compareTo(ZERO) <= 0) {
            throw new IllegalStateException("当前结算状态不允许主动支付");
        }
        PaymentAttemptResult result = walletClient.createPaymentAttempt(new PaymentAttemptRequest(
                orderNo, passengerId, settlement.getPayableAmount(), "MANUAL",
                command.channel().trim().toUpperCase(), idempotencyKey.trim()));
        validatePaymentIdentity(settlement, result);
        if (!Objects.equals(orderNo, result.getOrderNo())) {
            throw new IllegalArgumentException("钱包创建结果与订单不一致");
        }
        if (settlement.getActivePaymentNo() != null
                && !Objects.equals(settlement.getActivePaymentNo(), result.getPaymentNo())) {
            throw new IllegalStateException("订单已有另一笔支付处理中");
        }
        if (confirmingReplay) {
            if (!"CONFIRMING".equals(result.getStatus())) {
                applyPaymentResult(result);
            }
            return result;
        }
        if ("PAYING".equals(result.getStatus())) {
            int claimed = settlementMapper.update(null, Wrappers.<TripOrderSettlement>lambdaUpdate()
                    .set(TripOrderSettlement::getActivePaymentNo, result.getPaymentNo())
                    .set(TripOrderSettlement::getPaymentStatus, 0)
                    .set(TripOrderSettlement::getUpdatedAt, LocalDateTime.now())
                    .eq(TripOrderSettlement::getId, settlement.getId())
                    .and(active -> active.isNull(TripOrderSettlement::getActivePaymentNo)
                            .or().eq(TripOrderSettlement::getActivePaymentNo, result.getPaymentNo()))
                    .eq(TripOrderSettlement::getSettlementStatus, "PAYMENT_REQUIRED"));
            if (claimed != 1) {
                throw new IllegalStateException("主动支付状态登记失败，请重试");
            }
            return result;
        }
        applyPaymentResult(result);
        return result;
    }

    private boolean samePayment(PaymentResultNotification notice, PaymentAttemptResult actual) {
        return actual != null
                && Objects.equals(notice.paymentNo(), actual.getPaymentNo())
                && Objects.equals(notice.orderNo(), actual.getOrderNo())
                && Objects.equals(notice.passengerId(), actual.getPassengerId())
                && Objects.equals(notice.channel(), actual.getChannel())
                && notice.amount() != null && actual.getAmount() != null
                && notice.amount().compareTo(actual.getAmount()) == 0
                && Objects.equals(notice.status(), actual.getStatus())
                && Objects.equals(notice.channelTradeNo(), actual.getChannelTradeNo());
    }

    private String applyPaymentResult(PaymentAttemptResult result) {
        if (result == null || result.getStatus() == null) {
            throw new IllegalArgumentException("钱包未返回有效支付状态");
        }
        TripOrderSettlement settlement = getSettlement(result.getOrderNo());
        if (settlement == null) {
            throw new IllegalArgumentException("订单结算记录不存在");
        }
        validatePaymentIdentity(settlement, result);
        return switch (result.getStatus()) {
            case "SUCCESS" -> finalizeSuccessfulPayment(settlement, result);
            case "CONFIRMING" -> moveToConfirming(settlement, result.getPaymentNo());
            case "FAILED", "CANCELLED" -> moveToPaymentRequired(
                    settlement.getOrderNo(), result.getPaymentNo(), result.getStatus());
            case "DUPLICATE_SUCCESS" -> "DUPLICATE_SUCCESS";
            default -> throw new IllegalArgumentException("未知支付状态: " + result.getStatus());
        };
    }

    private void validatePaymentIdentity(TripOrderSettlement settlement, PaymentAttemptResult result) {
        if (result == null
                || !Objects.equals(settlement.getPassengerId(), result.getPassengerId())
                || settlement.getPayableAmount() == null || result.getAmount() == null
                || settlement.getPayableAmount().compareTo(result.getAmount()) != 0) {
            throw new IllegalArgumentException("支付金额或乘客与结算账单不一致");
        }
    }

    private String moveToConfirming(TripOrderSettlement settlement, String paymentNo) {
        settlementMapper.update(null, Wrappers.<TripOrderSettlement>lambdaUpdate()
                .set(TripOrderSettlement::getActivePaymentNo, paymentNo)
                .set(TripOrderSettlement::getPaymentStatus, 1)
                .set(TripOrderSettlement::getSettlementStatus, "PAY_CONFIRMING")
                .set(TripOrderSettlement::getFailureCode, null)
                .set(TripOrderSettlement::getFailureSummary, null)
                .set(TripOrderSettlement::getUpdatedAt, LocalDateTime.now())
                .eq(TripOrderSettlement::getId, settlement.getId())
                .isNull(TripOrderSettlement::getPaymentNo)
                .ne(TripOrderSettlement::getSettlementStatus, "PAID"));
        return "PAY_CONFIRMING";
    }

    private String moveToPaymentRequired(String orderNo, String paymentNo, String reason) {
        TripOrderSettlement settlement = getSettlement(orderNo);
        if (settlement == null) {
            throw new IllegalArgumentException("订单结算记录不存在");
        }
        var update = Wrappers.<TripOrderSettlement>lambdaUpdate()
                .set(TripOrderSettlement::getActivePaymentNo, null)
                .set(TripOrderSettlement::getPaymentStatus, 3)
                .set(TripOrderSettlement::getSettlementStatus, "PAYMENT_REQUIRED")
                .set(TripOrderSettlement::getFailureCode, reason)
                .set(TripOrderSettlement::getFailureSummary,
                        paymentNo == null ? reason : reason + " paymentNo=" + paymentNo)
                .set(TripOrderSettlement::getUpdatedAt, LocalDateTime.now())
                .eq(TripOrderSettlement::getId, settlement.getId())
                .isNull(TripOrderSettlement::getPaymentNo)
                .ne(TripOrderSettlement::getSettlementStatus, "PAID");
        if (paymentNo == null) {
            update.isNull(TripOrderSettlement::getActivePaymentNo);
        } else {
            update.and(active -> active.isNull(TripOrderSettlement::getActivePaymentNo)
                    .or().eq(TripOrderSettlement::getActivePaymentNo, paymentNo));
        }
        if (settlementMapper.update(null, update) != 1) {
            TripOrderSettlement latest = getSettlementForUpdate(orderNo);
            if (latest != null && latest.getActivePaymentNo() != null
                    && !Objects.equals(latest.getActivePaymentNo(), paymentNo)) {
                log.info("忽略旧支付尝试的延迟失败结果 orderNo={} stalePaymentNo={} activePaymentNo={}",
                        orderNo, paymentNo, latest.getActivePaymentNo());
                return "STALE_PAYMENT_RESULT";
            }
            return latest == null ? "SETTLEMENT_NOT_FOUND" : latest.getSettlementStatus();
        }
        if (!"PAYMENT_REQUIRED".equals(settlement.getSettlementStatus())) {
            insertOrderChangedOutbox(settlement, LocalDateTime.now());
        }
        return "PAYMENT_REQUIRED";
    }

    private String finalizeSuccessfulPayment(TripOrderSettlement settlement, PaymentAttemptResult payment) {
        if ("PAID".equals(settlement.getSettlementStatus())) {
            return Objects.equals(settlement.getPaymentNo(), payment.getPaymentNo())
                    ? "PAID" : "DUPLICATE_SUCCESS";
        }
        if (settlement.getPaymentNo() != null
                && !Objects.equals(settlement.getPaymentNo(), payment.getPaymentNo())) {
            log.error("检测到订单第二笔成功支付，保留首笔 orderNo={} first={} duplicate={}",
                    settlement.getOrderNo(), settlement.getPaymentNo(), payment.getPaymentNo());
            return "DUPLICATE_SUCCESS";
        }
        LocalDateTime now = LocalDateTime.now();
        if (settlement.getPaymentNo() == null) {
            int claimed = settlementMapper.update(null, Wrappers.<TripOrderSettlement>lambdaUpdate()
                    .set(TripOrderSettlement::getPaymentNo, payment.getPaymentNo())
                    .set(TripOrderSettlement::getActivePaymentNo, payment.getPaymentNo())
                    .set(TripOrderSettlement::getPaymentStatus, 1)
                    .set(TripOrderSettlement::getSettlementStatus, "PAY_CONFIRMING")
                    .set(TripOrderSettlement::getFailureCode, "PAYMENT_FINALIZE_PENDING")
                    .set(TripOrderSettlement::getFailureSummary, "支付成功，等待核券和结算收尾")
                    .set(TripOrderSettlement::getUpdatedAt, now)
                    .eq(TripOrderSettlement::getId, settlement.getId())
                    .isNull(TripOrderSettlement::getPaymentNo)
                    .ne(TripOrderSettlement::getSettlementStatus, "PAID"));
            if (claimed != 1) {
                TripOrderSettlement winner = getSettlementForUpdate(settlement.getOrderNo());
                return winner != null && Objects.equals(winner.getPaymentNo(), payment.getPaymentNo())
                        ? finalizeSuccessfulPayment(winner, payment) : "DUPLICATE_SUCCESS";
            }
            settlement.setPaymentNo(payment.getPaymentNo());
        }
        try {
            if (settlement.getCouponId() != null) {
                calculateClient.useCoupon(new CouponUseRequest(settlement.getPassengerId(),
                        settlement.getCouponId(), settlement.getOrderNo(),
                        settlement.getCouponDiscountAmount()));
            }
        } catch (RuntimeException ex) {
            log.error("支付成功后核销优惠券失败，保留收尾恢复标记 orderNo={} paymentNo={}",
                    settlement.getOrderNo(), payment.getPaymentNo(), ex);
            return "PAYMENT_FINALIZE_PENDING";
        }
        int unblocked = orderMapper.update(null, Wrappers.<TripOrder>lambdaUpdate()
                .set(TripOrder::getBlocksNewOrder, null)
                .set(TripOrder::getUpdatedAt, now)
                .eq(TripOrder::getOrderNo, settlement.getOrderNo())
                .eq(TripOrder::getBlocksNewOrder, 1));
        if (unblocked != 1) {
            TripOrder currentOrder = getOrder(settlement.getOrderNo());
            if (currentOrder == null || currentOrder.getBlocksNewOrder() != null) {
                settlementMapper.update(null, Wrappers.<TripOrderSettlement>lambdaUpdate()
                        .set(TripOrderSettlement::getFailureSummary, "支付成功但解除下单阻塞失败，等待恢复")
                        .set(TripOrderSettlement::getUpdatedAt, now)
                        .eq(TripOrderSettlement::getId, settlement.getId())
                        .eq(TripOrderSettlement::getPaymentNo, payment.getPaymentNo()));
                return "PAYMENT_FINALIZE_PENDING";
            }
        }
        int finalized = settlementMapper.update(null, Wrappers.<TripOrderSettlement>lambdaUpdate()
                .set(TripOrderSettlement::getActivePaymentNo, null)
                .set(TripOrderSettlement::getPaymentStatus, 2)
                .set(TripOrderSettlement::getPaidAmount, payment.getAmount())
                .set(TripOrderSettlement::getPaidAt,
                        payment.getOccurredAt() == null ? now : payment.getOccurredAt())
                .set(TripOrderSettlement::getSettlementStatus, "PAID")
                .set(TripOrderSettlement::getFailureCode, null)
                .set(TripOrderSettlement::getFailureSummary, null)
                .set(TripOrderSettlement::getSettledAt, now)
                .set(TripOrderSettlement::getUpdatedAt, now)
                .eq(TripOrderSettlement::getId, settlement.getId())
                .eq(TripOrderSettlement::getPaymentNo, payment.getPaymentNo())
                .ne(TripOrderSettlement::getSettlementStatus, "PAID"));
        if (finalized != 1) {
            TripOrderSettlement latest = getSettlementForUpdate(settlement.getOrderNo());
            if (latest != null && "PAID".equals(latest.getSettlementStatus())) {
                return "PAID";
            }
            orderMapper.update(null, Wrappers.<TripOrder>lambdaUpdate()
                    .set(TripOrder::getBlocksNewOrder, 1)
                    .set(TripOrder::getUpdatedAt, LocalDateTime.now())
                    .eq(TripOrder::getOrderNo, settlement.getOrderNo())
                    .isNull(TripOrder::getBlocksNewOrder));
            return "PAYMENT_FINALIZE_PENDING";
        }
        insertOrderChangedOutbox(settlement, now);
        return "PAID";
    }

    private void insertOrderChangedOutbox(TripOrderSettlement settlement, LocalDateTime now) {
        OrderOutboxEvent outbox = new OrderOutboxEvent()
                .setTopic("order.changed.v1")
                .setEventType("ORDER_CHANGED")
                .setAggregateId(settlement.getOrderNo())
                .setPayload("{}")
                .setStatus("PENDING")
                .setRetryCount(0)
                .setNextRetryAt(now)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        outboxMapper.insert(outbox);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1.0");
        payload.put("eventId", String.valueOf(outbox.getId()));
        payload.put("eventType", "ORDER_CHANGED");
        payload.put("orderNo", settlement.getOrderNo());
        payload.put("passengerId", settlement.getPassengerId());
        payload.put("occurredAt", now.toString());
        try {
            outbox.setPayload(objectMapper.writeValueAsString(payload));
            if (outboxMapper.updateById(outbox) != 1) {
                throw new IllegalStateException("订单变化事件payload回写失败");
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("订单变化事件序列化失败", ex);
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
        insertOrderChangedOutbox(settlement, now);
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

    /**
     * CAS 失败后使用当前读，避免 MySQL REPEATABLE READ 继续看到事务开始时的旧快照。
     */
    private TripOrderSettlement getSettlementForUpdate(String orderNo) {
        return settlementMapper.selectOne(Wrappers.<TripOrderSettlement>lambdaQuery()
                .eq(TripOrderSettlement::getOrderNo, orderNo)
                .last("LIMIT 1 FOR UPDATE"));
    }
}
