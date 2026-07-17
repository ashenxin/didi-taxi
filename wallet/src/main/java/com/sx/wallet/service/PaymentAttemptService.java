package com.sx.wallet.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.wallet.dao.WalletAutoPayAgreementMapper;
import com.sx.wallet.dao.WalletPaymentOrderMapper;
import com.sx.wallet.model.WalletAutoPayAgreement;
import com.sx.wallet.model.WalletPaymentOrder;
import com.sx.wallet.model.dto.CreatePaymentAttemptRequest;
import com.sx.wallet.model.dto.PaymentResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
public class PaymentAttemptService {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("10000.00");
    private static final int MAX_INSERT_RETRIES = 3;

    private final WalletAutoPayAgreementMapper agreementMapper;
    private final WalletPaymentOrderMapper paymentMapper;
    private final PaymentChannel paymentChannel;
    private final int checkoutMinutes;
    private final byte[] checkoutTokenSecret;

    public PaymentAttemptService(WalletAutoPayAgreementMapper agreementMapper,
                                 WalletPaymentOrderMapper paymentMapper,
                                 PaymentChannel paymentChannel,
                                 @Value("${wallet.payment.checkout-minutes:10}") int checkoutMinutes,
                                 @Value("${wallet.payment.checkout-token-secret:local-dev-only-change-me}")
                                 String checkoutTokenSecret) {
        this.agreementMapper = agreementMapper;
        this.paymentMapper = paymentMapper;
        this.paymentChannel = paymentChannel;
        this.checkoutMinutes = Math.max(1, checkoutMinutes);
        if (checkoutTokenSecret == null || checkoutTokenSecret.length() < 16) {
            throw new IllegalArgumentException("checkout-token-secret长度不能少于16位");
        }
        this.checkoutTokenSecret = checkoutTokenSecret.getBytes(StandardCharsets.UTF_8);
    }

    public PaymentResult create(CreatePaymentAttemptRequest request) {
        validateRequest(request);
        WalletPaymentOrder idempotent = findByIdempotencyKey(request.getIdempotencyKey());
        if (idempotent != null) {
            return idempotentResult(request, idempotent);
        }
        WalletPaymentOrder success = findByOrderAndStatus(request.getOrderNo(), "SUCCESS");
        if (success != null) {
            return toResult(success);
        }
        WalletPaymentOrder active = findActive(request.getOrderNo());
        if (active != null) {
            throw new IllegalArgumentException("订单支付处理中，请勿重复发起");
        }

        String triggerType = normalizeTrigger(request.getTriggerType());
        WalletAutoPayAgreement agreement = null;
        String channel;
        if ("AUTO_PAY".equals(triggerType)) {
            agreement = findDefaultAgreement(request.getPassengerId());
            if (agreement == null) {
                throw new IllegalArgumentException("未开通默认免密支付");
            }
            channel = normalizeChannel(agreement.getChannel());
        } else {
            channel = normalizeChannel(request.getChannel());
        }

        BigDecimal amount = request.getAmount().setScale(2, RoundingMode.HALF_UP);
        for (int retry = 0; retry < MAX_INSERT_RETRIES; retry++) {
            int attemptNo = nextAttemptNo(request.getOrderNo());
            WalletPaymentOrder attempt = newAttempt(request, triggerType, channel, agreement, amount,
                    attemptNo);
            try {
                paymentMapper.insert(attempt);
                if ("AUTO_PAY".equals(triggerType)) {
                    executeAutoPay(attempt);
                }
                return toResult(attempt);
            } catch (DuplicateKeyException ex) {
                WalletPaymentOrder sameRequest = findByIdempotencyKey(request.getIdempotencyKey());
                if (sameRequest != null) {
                    return idempotentResult(request, sameRequest);
                }
                WalletPaymentOrder winningSuccess = findByOrderAndStatus(request.getOrderNo(), "SUCCESS");
                if (winningSuccess != null) {
                    return toResult(winningSuccess);
                }
                if (findActive(request.getOrderNo()) != null) {
                    throw new IllegalArgumentException("订单支付处理中，请勿重复发起");
                }
                if (retry == MAX_INSERT_RETRIES - 1) {
                    throw new IllegalStateException("支付尝试序号竞争失败，请稍后重试", ex);
                }
            }
        }
        throw new IllegalStateException("支付尝试创建失败");
    }

    private WalletPaymentOrder newAttempt(CreatePaymentAttemptRequest request,
                                          String triggerType,
                                          String channel,
                                          WalletAutoPayAgreement agreement,
                                          BigDecimal amount,
                                          int attemptNo) {
        LocalDateTime now = LocalDateTime.now();
        WalletPaymentOrder attempt = new WalletPaymentOrder()
                .setPaymentNo("PAY" + UUID.randomUUID().toString().replace("-", ""))
                .setOrderNo(request.getOrderNo())
                .setPassengerId(request.getPassengerId())
                .setTriggerType(triggerType)
                .setAttemptNo(attemptNo)
                .setChannel(channel)
                .setAgreementId(agreement == null ? null : agreement.getId())
                .setAmount(amount)
                .setStatus("PAYING")
                .setChannelRequestNo("REQ" + UUID.randomUUID().toString().replace("-", ""))
                .setIdempotencyKey(request.getIdempotencyKey())
                .setNotifyPayload("{}")
                .setCreatedAt(now)
                .setUpdatedAt(now);
        if ("MANUAL".equals(triggerType)) {
            attempt.setCheckoutTokenExpiresAt(now.plusMinutes(checkoutMinutes).withNano(0));
            attempt.setCheckoutTokenHash(sha256(reconstructCheckoutToken(attempt)));
        }
        return attempt;
    }

    private void executeAutoPay(WalletPaymentOrder attempt) {
        try {
            applyChannelResult(attempt, paymentChannel.initiate(new PaymentChannel.ChannelCommand(
                    attempt.getPaymentNo(), attempt.getChannelRequestNo(), attempt.getOrderNo(),
                    attempt.getPassengerId(), attempt.getChannel(), attempt.getAmount())));
        } catch (RuntimeException ex) {
            LocalDateTime now = LocalDateTime.now();
            attempt.setStatus("FAILED")
                    .setFailedReason("CHANNEL_CALL_FAILED: " + ex.getMessage())
                    .setResolvedAt(now)
                    .setUpdatedAt(now);
        }
        if (paymentMapper.updateById(attempt) != 1) {
            throw new IllegalStateException("支付尝试结果持久化失败 paymentNo=" + attempt.getPaymentNo());
        }
    }

    private void applyChannelResult(WalletPaymentOrder attempt, PaymentChannel.ChannelResult result) {
        if (result == null || result.status() == null) {
            throw new IllegalStateException("支付渠道未返回有效结果");
        }
        String status = result.status().trim().toUpperCase(Locale.ROOT);
        if (!"SUCCESS".equals(status) && !"FAILED".equals(status) && !"CONFIRMING".equals(status)) {
            throw new IllegalStateException("支付渠道返回未知状态: " + status);
        }
        LocalDateTime now = LocalDateTime.now();
        attempt.setStatus(status)
                .setChannelTradeNo(result.channelTradeNo())
                .setFailedReason(result.failedReason())
                .setUpdatedAt(now);
        if ("SUCCESS".equals(status)) {
            attempt.setPaidAt(now).setResolvedAt(now);
        } else if ("FAILED".equals(status)) {
            attempt.setResolvedAt(now);
        }
    }

    private int nextAttemptNo(String orderNo) {
        WalletPaymentOrder latest = paymentMapper.selectOne(Wrappers.<WalletPaymentOrder>lambdaQuery()
                .eq(WalletPaymentOrder::getOrderNo, orderNo)
                .orderByDesc(WalletPaymentOrder::getAttemptNo)
                .last("LIMIT 1"));
        return latest == null || latest.getAttemptNo() == null ? 1 : latest.getAttemptNo() + 1;
    }

    private WalletPaymentOrder findByIdempotencyKey(String idempotencyKey) {
        return paymentMapper.selectOne(Wrappers.<WalletPaymentOrder>lambdaQuery()
                .eq(WalletPaymentOrder::getIdempotencyKey, idempotencyKey).last("LIMIT 1"));
    }

    private WalletPaymentOrder findByOrderAndStatus(String orderNo, String status) {
        return paymentMapper.selectOne(Wrappers.<WalletPaymentOrder>lambdaQuery()
                .eq(WalletPaymentOrder::getOrderNo, orderNo)
                .eq(WalletPaymentOrder::getStatus, status)
                .last("LIMIT 1"));
    }

    private WalletPaymentOrder findActive(String orderNo) {
        return paymentMapper.selectOne(Wrappers.<WalletPaymentOrder>lambdaQuery()
                .eq(WalletPaymentOrder::getOrderNo, orderNo)
                .in(WalletPaymentOrder::getStatus, "PAYING", "CONFIRMING", "PROCESSING")
                .last("LIMIT 1"));
    }

    private WalletAutoPayAgreement findDefaultAgreement(Long passengerId) {
        return agreementMapper.selectOne(Wrappers.<WalletAutoPayAgreement>lambdaQuery()
                .eq(WalletAutoPayAgreement::getPassengerId, passengerId)
                .eq(WalletAutoPayAgreement::getAgreementStatus, "ACTIVE")
                .eq(WalletAutoPayAgreement::getIsDefault, 1)
                .eq(WalletAutoPayAgreement::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private void validateRequest(CreatePaymentAttemptRequest request) {
        if (request == null || request.getOrderNo() == null || request.getOrderNo().isBlank()
                || request.getPassengerId() == null || request.getPassengerId() <= 0
                || request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()
                || request.getAmount() == null) {
            throw new IllegalArgumentException("支付尝试参数不完整");
        }
        BigDecimal amount = request.getAmount().setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(new BigDecimal("0.01")) < 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("支付金额必须在0.01至10000.00之间");
        }
    }

    private String normalizeTrigger(String triggerType) {
        String value = triggerType == null ? "" : triggerType.trim().toUpperCase(Locale.ROOT);
        if (!"AUTO_PAY".equals(value) && !"MANUAL".equals(value)) {
            throw new IllegalArgumentException("triggerType仅支持AUTO_PAY/MANUAL");
        }
        return value;
    }

    private String normalizeChannel(String channel) {
        String value = channel == null ? "" : channel.trim().toUpperCase(Locale.ROOT);
        if (!"ALIPAY".equals(value) && !"WECHAT".equals(value)) {
            throw new IllegalArgumentException("仅支持ALIPAY/WECHAT");
        }
        return value;
    }

    private PaymentResult idempotentResult(CreatePaymentAttemptRequest request, WalletPaymentOrder existing) {
        String trigger = normalizeTrigger(request.getTriggerType());
        BigDecimal amount = request.getAmount().setScale(2, RoundingMode.HALF_UP);
        boolean same = Objects.equals(existing.getOrderNo(), request.getOrderNo())
                && Objects.equals(existing.getPassengerId(), request.getPassengerId())
                && existing.getAmount() != null && existing.getAmount().compareTo(amount) == 0
                && Objects.equals(existing.getTriggerType(), trigger);
        if (same && "MANUAL".equals(trigger)) {
            same = Objects.equals(existing.getChannel(), normalizeChannel(request.getChannel()));
        }
        if (!same) {
            throw new IllegalArgumentException("幂等键冲突：请求参数与原支付尝试不一致");
        }
        return toResult(existing);
    }

    private PaymentResult toResult(WalletPaymentOrder attempt) {
        PaymentResult result = new PaymentResult();
        result.setPaymentNo(attempt.getPaymentNo());
        result.setStatus(attempt.getStatus());
        result.setChannel(attempt.getChannel());
        result.setAttemptNo(attempt.getAttemptNo());
        result.setAmount(attempt.getAmount());
        if ("MANUAL".equals(attempt.getTriggerType()) && "PAYING".equals(attempt.getStatus())
                && attempt.getCheckoutTokenExpiresAt() != null
                && attempt.getCheckoutTokenExpiresAt().isAfter(LocalDateTime.now())) {
            String checkoutToken = reconstructCheckoutToken(attempt);
            if (!MessageDigest.isEqual(sha256(checkoutToken).getBytes(StandardCharsets.UTF_8),
                    attempt.getCheckoutTokenHash().getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalStateException("收银台token校验失败");
            }
            result.setCheckoutUrl("/mock-cashier/" + attempt.getPaymentNo() + "?token=" + checkoutToken);
        }
        return result;
    }

    private String reconstructCheckoutToken(WalletPaymentOrder attempt) {
        String payload = attempt.getPaymentNo() + "|" + attempt.getCheckoutTokenExpiresAt();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(checkoutTokenSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("收银台token签名失败", ex);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256不可用", ex);
        }
    }
}
