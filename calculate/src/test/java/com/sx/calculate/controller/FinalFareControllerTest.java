package com.sx.calculate.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.calculate.model.dto.FareRuleSnapshot;
import com.sx.calculate.model.dto.FinalFareRequest;
import com.sx.calculate.model.dto.FinalFareResult;
import com.sx.calculate.service.FareCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class FinalFareControllerTest {

    @Test
    void calculatesFinalFareOnlyFromFrozenSnapshotAndMetrics() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        FareCalculator calculator = new FareCalculator(objectMapper, new BigDecimal("10000.00"));
        FinalFareController controller = new FinalFareController(calculator);
        FinalFareRequest request = new FinalFareRequest();
        request.setFareRuleSnapshot(objectMapper.writeValueAsString(snapshot()));
        request.setFareCalculationVersion("fare-v1");
        request.setBillingDistanceMeters(12_340L);
        request.setBillingDurationSeconds(1_560L);

        FinalFareResult result = controller.calculate(request).getData();

        assertThat(result.getFinalAmount()).isEqualByComparingTo("44.95");
        assertThat(result.getFareCalculationVersion()).isEqualTo("fare-v1");
        assertThat(result.getBillingDistanceMeters()).isEqualTo(12_340L);
        assertThat(result.getBillingDurationSeconds()).isEqualTo(1_560L);
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
}
