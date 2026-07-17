package com.sx.map.service;

import com.sx.map.model.dto.Point;
import com.sx.map.model.dto.RouteRequest;
import com.sx.map.model.dto.RouteResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class MockRouteProvider {

    private static final String PROVIDER = "LOCAL_MOCK_ROUTE";

    private final String version;
    private final long minDistanceMeters;
    private final long maxDistanceMeters;
    private final double minMetersPerSecond;
    private final double maxMetersPerSecond;

    public MockRouteProvider(
            @Value("${map.mock-route.version:mock-route-v1}") String version,
            @Value("${map.mock-route.min-distance-meters:3000}") long minDistanceMeters,
            @Value("${map.mock-route.max-distance-meters:30000}") long maxDistanceMeters,
            @Value("${map.mock-route.min-meters-per-second:6.0}") double minMetersPerSecond,
            @Value("${map.mock-route.max-meters-per-second:14.0}") double maxMetersPerSecond) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("mock路线版本不能为空");
        }
        if (minDistanceMeters <= 0 || maxDistanceMeters < minDistanceMeters) {
            throw new IllegalArgumentException("mock路线距离范围不合法");
        }
        if (minMetersPerSecond <= 0 || maxMetersPerSecond < minMetersPerSecond) {
            throw new IllegalArgumentException("mock路线速度范围不合法");
        }
        this.version = version.trim();
        this.minDistanceMeters = minDistanceMeters;
        this.maxDistanceMeters = maxDistanceMeters;
        this.minMetersPerSecond = minMetersPerSecond;
        this.maxMetersPerSecond = maxMetersPerSecond;
    }

    public RouteResponse route(RouteRequest request) {
        byte[] digest = sha256(version + "|" + canonical(request));
        BigInteger hash = new BigInteger(1, digest);
        long distanceRange = maxDistanceMeters - minDistanceMeters + 1;
        long distanceMeters = minDistanceMeters
                + hash.mod(BigInteger.valueOf(distanceRange)).longValueExact();
        long speedSeed = new BigInteger(1, digest, 8, 7).longValueExact();
        double speedRatio = speedSeed / (double) 0x00FFFFFFFFFFFFFFL;
        double metersPerSecond = minMetersPerSecond
                + (maxMetersPerSecond - minMetersPerSecond) * speedRatio;
        long durationSeconds = Math.max(60L, Math.round(distanceMeters / metersPerSecond));

        RouteResponse response = new RouteResponse();
        response.setDistanceMeters(distanceMeters);
        response.setDurationSeconds(durationSeconds);
        response.setProvider(PROVIDER);
        response.setVersion(version);
        response.setTraceId(HexFormat.of().formatHex(digest, 0, 12));
        return response;
    }

    private static String canonical(RouteRequest request) {
        if (request == null || request.getOrigin() == null || request.getDest() == null) {
            throw new IllegalArgumentException("起终点不能为空");
        }
        return canonical(request.getOrigin()) + "|" + canonical(request.getDest());
    }

    private static String canonical(Point point) {
        if (point.getLat() == null || point.getLng() == null) {
            throw new IllegalArgumentException("经纬度不能为空");
        }
        return String.format(Locale.ROOT, "%.7f,%.7f", point.getLat(), point.getLng());
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前JVM不支持SHA-256", e);
        }
    }
}
