package com.sx.capacity.service.geo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 听单司机 Redis GEO 索引（按城市分 key）；库表运力状态仍为权威，本池为匹配加速。
 */
@Component
@Slf4j
public class DriverGeoRedisPool {

    private static final String KEY_PREFIX = "tx:driver:geo:";

    private final StringRedisTemplate redis;
    private final int geoTtlSeconds;

    public DriverGeoRedisPool(StringRedisTemplate redis,
                              @Value("${capacity.dispatch.driver-geo-ttl-seconds:600}") int geoTtlSeconds) {
        this.redis = redis;
        this.geoTtlSeconds = geoTtlSeconds;
    }

    public void add(String cityCode, Long driverId, double lat, double lng) {
        if (cityCode == null || cityCode.isBlank() || driverId == null) {
            return;
        }
        String key = key(cityCode);
        Point point = new Point(lng, lat);
        try {
            redis.opsForGeo().add(key, point, String.valueOf(driverId));
            redis.expire(key, Duration.ofSeconds(Math.max(60, geoTtlSeconds)));
        } catch (Exception e) {
            log.warn("driver geo add failed cityCode={} driverId={}: {}", cityCode, driverId, e.toString());
        }
    }

    public void remove(String cityCode, Long driverId) {
        if (cityCode == null || cityCode.isBlank() || driverId == null) {
            return;
        }
        String key = key(cityCode);
        try {
            redis.opsForGeo().remove(key, String.valueOf(driverId));
        } catch (Exception e) {
            log.warn("driver geo remove failed cityCode={} driverId={}: {}", cityCode, driverId, e.toString());
        }
    }

    /**
     * 以上车点为圆心，按距离升序返回半径内的司机 ID（直线距离过滤由 GEO 半径表达）。
     */
    public List<Long> listNearestDriverIds(String cityCode, double originLat, double originLng,
                                           double radiusMeters, int limit) {
        if (cityCode == null || cityCode.isBlank() || limit <= 0) {
            return List.of();
        }
        String redisKey = key(cityCode);
        double radiusKm = radiusMeters / 1000.0;
        Circle circle = new Circle(new Point(originLng, originLat), new Distance(radiusKm, Metrics.KILOMETERS));
        RedisGeoCommands.GeoRadiusCommandArgs args = RedisGeoCommands.GeoRadiusCommandArgs
                .newGeoRadiusArgs()
                .sortAscending()
                .limit(limit);
        try {
            GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                    redis.opsForGeo().radius(redisKey, circle, args);
            if (results == null) {
                return List.of();
            }
            List<Long> out = new ArrayList<>();
            for (GeoResult<RedisGeoCommands.GeoLocation<String>> r : results.getContent()) {
                RedisGeoCommands.GeoLocation<String> loc = r.getContent();
                if (loc == null) {
                    continue;
                }
                String name = loc.getName();
                if (name != null) {
                    try {
                        out.add(Long.parseLong(name));
                    } catch (NumberFormatException ignored) {
                        // skip corrupt member
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("driver geo radius failed cityCode={}: {}", cityCode, e.toString());
            return List.of();
        }
    }

    public Optional<Long> findNearestDriverId(String cityCode, double originLat, double originLng, double radiusMeters) {
        List<Long> ids = listNearestDriverIds(cityCode, originLat, originLng, radiusMeters, 1);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.getFirst());
    }

    private static String key(String cityCode) {
        return KEY_PREFIX + cityCode;
    }
}
