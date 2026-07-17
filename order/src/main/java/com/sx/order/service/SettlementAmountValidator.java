package com.sx.order.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class SettlementAmountValidator {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final BigDecimal PLATFORM_SERVICE_FEE_RATE = new BigDecimal("0.0500");
    private final BigDecimal maxFinalAmount;

    public SettlementAmountValidator(
            @Value("${order.settlement.max-final-amount:10000.00}") BigDecimal maxFinalAmount) {
        this.maxFinalAmount = scale(maxFinalAmount);
    }

    public ValidatedAmounts validate(BigDecimal finalAmount, BigDecimal discountAmount,
                                     BigDecimal payableAmount) {
        if (finalAmount == null || discountAmount == null || payableAmount == null) {
            throw invalid("金额不能为空");
        }
        BigDecimal finalRounded = scale(finalAmount);
        BigDecimal discountRounded = scale(discountAmount);
        BigDecimal payableRounded = scale(payableAmount);
        if (finalRounded.compareTo(new BigDecimal("0.01")) < 0
                || finalRounded.compareTo(maxFinalAmount) > 0
                || discountRounded.compareTo(ZERO) < 0
                || discountRounded.compareTo(finalRounded) > 0
                || payableRounded.compareTo(ZERO) < 0
                || payableRounded.compareTo(finalRounded) > 0
                || payableRounded.compareTo(finalRounded.subtract(discountRounded)) != 0) {
            throw invalid("金额越界或关系不成立");
        }
        return new ValidatedAmounts(finalRounded, discountRounded, payableRounded);
    }

    public ValidatedDistribution validateDistribution(BigDecimal payableAmount,
                                                       BigDecimal platformServiceFeeRate,
                                                       BigDecimal platformServiceFeeAmount,
                                                       BigDecimal carrierIncomeAmount) {
        if (payableAmount == null || platformServiceFeeRate == null
                || platformServiceFeeAmount == null || carrierIncomeAmount == null) {
            throw invalid("分账金额不能为空");
        }
        BigDecimal payableRounded = scale(payableAmount);
        BigDecimal rateRounded = platformServiceFeeRate.setScale(4, RoundingMode.HALF_UP);
        BigDecimal feeRounded = scale(platformServiceFeeAmount);
        BigDecimal carrierRounded = scale(carrierIncomeAmount);
        BigDecimal expectedFee = scale(payableRounded.multiply(PLATFORM_SERVICE_FEE_RATE));
        if (payableRounded.compareTo(ZERO) < 0
                || rateRounded.compareTo(PLATFORM_SERVICE_FEE_RATE) != 0
                || feeRounded.compareTo(ZERO) < 0
                || carrierRounded.compareTo(ZERO) < 0
                || feeRounded.compareTo(expectedFee) != 0
                || carrierRounded.compareTo(payableRounded.subtract(feeRounded)) != 0) {
            throw invalid("平台服务费或承运方收入关系不成立");
        }
        return new ValidatedDistribution(rateRounded, feeRounded, carrierRounded);
    }

    private static BigDecimal scale(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("AMOUNT_OUT_OF_RANGE: " + detail);
    }

    public record ValidatedAmounts(BigDecimal finalAmount, BigDecimal discountAmount,
                                   BigDecimal payableAmount) {
    }

    public record ValidatedDistribution(BigDecimal platformServiceFeeRate,
                                        BigDecimal platformServiceFeeAmount,
                                        BigDecimal carrierIncomeAmount) {
    }
}
