package com.sx.order.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementAmountValidatorTest {

    private final SettlementAmountValidator validator =
            new SettlementAmountValidator(new BigDecimal("10000.00"));

    @Test
    void roundsHalfUpBeforeValidatingRelations() {
        SettlementAmountValidator.ValidatedAmounts amounts = validator.validate(
                new BigDecimal("30.005"), new BigDecimal("5.004"), new BigDecimal("25.006"));

        assertThat(amounts.finalAmount()).isEqualByComparingTo("30.01");
        assertThat(amounts.discountAmount()).isEqualByComparingTo("5.00");
        assertThat(amounts.payableAmount()).isEqualByComparingTo("25.01");
    }

    @Test
    void rejectsNullNegativeExcessAndBrokenRelations() {
        assertThatThrownBy(() -> validator.validate(null, BigDecimal.ZERO, BigDecimal.ZERO))
                .hasMessageContaining("AMOUNT_OUT_OF_RANGE");
        assertThatThrownBy(() -> validator.validate(new BigDecimal("-1"), BigDecimal.ZERO, BigDecimal.ZERO))
                .hasMessageContaining("AMOUNT_OUT_OF_RANGE");
        assertThatThrownBy(() -> validator.validate(new BigDecimal("10000.01"), BigDecimal.ZERO,
                new BigDecimal("10000.01"))).hasMessageContaining("AMOUNT_OUT_OF_RANGE");
        assertThatThrownBy(() -> validator.validate(new BigDecimal("30"), new BigDecimal("31"), BigDecimal.ZERO))
                .hasMessageContaining("AMOUNT_OUT_OF_RANGE");
        assertThatThrownBy(() -> validator.validate(new BigDecimal("30"), new BigDecimal("5"),
                new BigDecimal("24.99"))).hasMessageContaining("AMOUNT_OUT_OF_RANGE");
    }

    @Test
    void allowsZeroPayableAndExactUpperFareBoundary() {
        SettlementAmountValidator.ValidatedAmounts zero = validator.validate(
                new BigDecimal("30"), new BigDecimal("30"), BigDecimal.ZERO);
        SettlementAmountValidator.ValidatedAmounts upper = validator.validate(
                new BigDecimal("10000"), BigDecimal.ZERO, new BigDecimal("10000"));

        assertThat(zero.payableAmount()).isZero();
        assertThat(upper.finalAmount()).isEqualByComparingTo("10000.00");
    }

    @Test
    void validatesFixedPlatformFeeAndNonNegativeCarrierIncome() {
        SettlementAmountValidator.ValidatedDistribution distribution = validator.validateDistribution(
                new BigDecimal("10000"), new BigDecimal("0.0500"),
                new BigDecimal("500"), new BigDecimal("9500"));

        assertThat(distribution.platformServiceFeeRate()).isEqualByComparingTo("0.0500");
        assertThat(distribution.platformServiceFeeAmount()).isEqualByComparingTo("500.00");
        assertThat(distribution.carrierIncomeAmount()).isEqualByComparingTo("9500.00");
        assertThatThrownBy(() -> validator.validateDistribution(
                new BigDecimal("30"), new BigDecimal("0.0600"),
                new BigDecimal("1.80"), new BigDecimal("28.20")))
                .hasMessageContaining("AMOUNT_OUT_OF_RANGE");
        assertThatThrownBy(() -> validator.validateDistribution(
                new BigDecimal("30"), new BigDecimal("0.0500"),
                new BigDecimal("31"), new BigDecimal("-1")))
                .hasMessageContaining("AMOUNT_OUT_OF_RANGE");
    }
}
