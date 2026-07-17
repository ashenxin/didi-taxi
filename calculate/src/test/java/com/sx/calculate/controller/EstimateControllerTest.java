package com.sx.calculate.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.calculate.dao.FareRuleEntityMapper;
import com.sx.calculate.model.FareRule;
import com.sx.calculate.model.dto.EstimateFareBody;
import com.sx.calculate.model.dto.EstimateFareResult;
import com.sx.calculate.service.FareCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EstimateControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void estimateReturnsCompleteFareRuleSnapshotAndVersion() throws Exception {
        FareRuleEntityMapper mapper = mock(FareRuleEntityMapper.class);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(rule());
        EstimateController controller = new EstimateController(mapper, objectMapper,
                new FareCalculator(objectMapper, new BigDecimal("10000.00")));

        EstimateFareResult result = controller.estimate(request()).getData();

        assertThat(result.getRuleId()).isEqualTo(7L);
        assertThat(result.getFareCalculationVersion()).isEqualTo("fare-v1");
        JsonNode snapshot = objectMapper.readTree(result.getFareRuleSnapshot());
        assertThat(snapshot.get("baseFare").decimalValue()).isEqualByComparingTo("12.00");
        assertThat(snapshot.get("includedDistanceKm").decimalValue()).isEqualByComparingTo("3.00");
        assertThat(snapshot.get("includedDurationMin").intValue()).isEqualTo(10);
        assertThat(snapshot.get("perKmPrice").decimalValue()).isEqualByComparingTo("2.50");
        assertThat(snapshot.get("perMinutePrice").decimalValue()).isEqualByComparingTo("0.60");
        assertThat(snapshot.get("minimumFare").decimalValue()).isEqualByComparingTo("15.00");
        assertThat(snapshot.get("maximumFare").decimalValue()).isEqualByComparingTo("500.00");
    }

    private static FareRule rule() {
        return new FareRule()
                .setId(7L)
                .setCompanyId(9L)
                .setProvinceCode("330000")
                .setCityCode("330100")
                .setProductCode("ECONOMY")
                .setRuleName("杭州快车")
                .setEffectiveFrom(LocalDateTime.of(2026, 1, 1, 0, 0))
                .setBaseFare(new BigDecimal("12.00"))
                .setIncludedDistanceKm(new BigDecimal("3.00"))
                .setIncludedDurationMin(10)
                .setPerKmPrice(new BigDecimal("2.50"))
                .setPerMinutePrice(new BigDecimal("0.60"))
                .setMinimumFare(new BigDecimal("15.00"))
                .setMaximumFare(new BigDecimal("500.00"));
    }

    private static EstimateFareBody request() {
        EstimateFareBody body = new EstimateFareBody();
        body.setCompanyId(9L);
        body.setProvinceCode("330000");
        body.setCityCode("330100");
        body.setProductCode("ECONOMY");
        body.setDistanceMeters(12_340L);
        body.setDurationSeconds(1_560L);
        return body;
    }
}
