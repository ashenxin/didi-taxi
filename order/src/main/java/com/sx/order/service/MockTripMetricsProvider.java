package com.sx.order.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class MockTripMetricsProvider {

    private final String version;
    private final int maxVariationPercent;

    public MockTripMetricsProvider(
            @Value("${order.settlement.mock-trip.version:mock-trip-v1}") String version,
            @Value("${order.settlement.mock-trip.max-variation-percent:0}") int maxVariationPercent) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("mock行程指标版本不能为空");
        }
        if (maxVariationPercent < 0 || maxVariationPercent > 100) {
            throw new IllegalArgumentException("mock时长浮动比例必须在0到100之间");
        }
        this.version = version.trim();
        this.maxVariationPercent = maxVariationPercent;
    }

    public long generateDurationSeconds(String orderNo, long plannedDurationSeconds) {
        if (orderNo == null || orderNo.isBlank() || plannedDurationSeconds <= 0) {
            throw new IllegalArgumentException("mock行程指标输入不合法");
        }
        if (maxVariationPercent == 0) {
            return plannedDurationSeconds;
        }
        byte[] digest = sha256(version + "|" + orderNo);
        int seed = ((digest[0] & 0xff) << 24)
                | ((digest[1] & 0xff) << 16)
                | ((digest[2] & 0xff) << 8)
                | (digest[3] & 0xff);
        int variation = Math.floorMod(seed, maxVariationPercent * 2 + 1) - maxVariationPercent;
        return Math.max(60L, Math.round(plannedDurationSeconds * (100.0d + variation) / 100.0d));
    }

    public String version() {
        return version;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前JVM不支持SHA-256", e);
        }
    }
}
