package com.sx.calculate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.calculate.model.dto.FareRuleSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FareCalculatorTest {

    private FareCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new FareCalculator(new ObjectMapper(), new BigDecimal("10000.00"));
    }

    @Test
    void calculatesFromFrozenSnapshotUsingHalfUpRounding() throws Exception {
        String snapshot = new ObjectMapper().writeValueAsString(snapshot());

        BigDecimal amount = calculator.calculate(snapshot, "fare-v1", 12_340L, 1_560L);

        assertThat(amount).isEqualByComparingTo("44.95");
    }

    @Test
    void exactUpperBoundaryIsAllowed() throws Exception {
        FareRuleSnapshot snapshot = snapshot();
        snapshot.setBaseFare(new BigDecimal("10000.004"));
        snapshot.setIncludedDistanceKm(new BigDecimal("100"));
        snapshot.setIncludedDurationMin(1000);
        snapshot.setMaximumFare(null);

        BigDecimal amount = calculator.calculate(json(snapshot), "fare-v1", 0L, 0L);

        assertThat(amount).isEqualByComparingTo("10000.00");
    }

    @Test
    void roundedAmountAboveUpperBoundaryIsRejected() throws Exception {
        FareRuleSnapshot snapshot = snapshot();
        snapshot.setBaseFare(new BigDecimal("10000.005"));
        snapshot.setIncludedDistanceKm(new BigDecimal("100"));
        snapshot.setIncludedDurationMin(1000);
        snapshot.setMaximumFare(null);

        assertThatThrownBy(() -> calculator.calculate(json(snapshot), "fare-v1", 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AMOUNT_OUT_OF_RANGE");
    }

    @Test
    void zeroNegativeAndMissingSnapshotValuesAreRejected() throws Exception {
        FareRuleSnapshot zero = snapshot();
        zero.setBaseFare(BigDecimal.ZERO);
        zero.setIncludedDistanceKm(new BigDecimal("100"));
        zero.setIncludedDurationMin(1000);
        zero.setMinimumFare(null);
        FareRuleSnapshot missing = snapshot();
        missing.setPerKmPrice(null);

        assertThatThrownBy(() -> calculator.calculate(json(zero), "fare-v1", 0L, 0L))
                .hasMessageContaining("AMOUNT_OUT_OF_RANGE");
        assertThatThrownBy(() -> calculator.calculate(json(missing), "fare-v1", 0L, 0L))
                .hasMessageContaining("计价规则快照不完整");
        assertThatThrownBy(() -> calculator.calculate(json(snapshot()), "fare-v1", -1L, 0L))
                .hasMessageContaining("计价指标不合法");
    }

    private static FareRuleSnapshot snapshot() {
        FareRuleSnapshot snapshot = new FareRuleSnapshot();
        snapshot.setRuleId(7L);
        snapshot.setBaseFare(new BigDecimal("12.00"));
        snapshot.setIncludedDistanceKm(new BigDecimal("3.00"));
        snapshot.setIncludedDurationMin(10);
        snapshot.setPerKmPrice(new BigDecimal("2.50"));
        snapshot.setPerMinutePrice(new BigDecimal("0.60"));
        snapshot.setMinimumFare(new BigDecimal("15.00"));
        snapshot.setMaximumFare(new BigDecimal("500.00"));
        return snapshot;
    }

    private static String json(FareRuleSnapshot snapshot) throws Exception {
        return new ObjectMapper().writeValueAsString(snapshot);
    }
}
