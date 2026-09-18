package com.sx.map.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sx.map.ai.model.PassengerRouteSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PassengerRouteSnapshotReplayTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final PassengerRouteConditionsCache conditions = mock(PassengerRouteConditionsCache.class);
    private final PassengerRouteSnapshotCache cache = new PassengerRouteSnapshotCache(redis, mapper, conditions);

    @Test
    void restoresOnlyCurrentOwnedSnapshot() throws Exception {
        PassengerRouteSnapshot snapshot = snapshot(Instant.now().minusSeconds(10), Instant.now().plusSeconds(200));
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(mapper.writeValueAsString(snapshot));
        when(conditions.find(42L, "AIC-1", 3L)).thenReturn(Optional.of(new
                PassengerRouteConditionsCache.ConfirmedConditions(42L, "AIC-1", 3L,
                snapshot.origin(), snapshot.destination(), Instant.now().minusSeconds(10),
                Instant.now().plusSeconds(200))));

        assertThat(cache.findForReplay(snapshot.routeRef(), 42L, "AIC-1")).contains(snapshot);
        assertThat(cache.findForReplay(snapshot.routeRef(), 43L, "AIC-1")).isEmpty();
        assertThat(cache.findForReplay(snapshot.routeRef(), 42L, "AIC-other")).isEmpty();
    }

    @Test
    void expiredSnapshotCannotRestorePolyline() throws Exception {
        PassengerRouteSnapshot snapshot = snapshot(Instant.now().minusSeconds(400), Instant.now().minusSeconds(100));
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(mapper.writeValueAsString(snapshot));
        when(conditions.find(42L, "AIC-1", 3L)).thenReturn(Optional.of(new
                PassengerRouteConditionsCache.ConfirmedConditions(42L, "AIC-1", 3L,
                snapshot.origin(), snapshot.destination(), Instant.now().minusSeconds(10),
                Instant.now().plusSeconds(200))));
        assertThat(cache.findForReplay(snapshot.routeRef(), 42L, "AIC-1")).isEmpty();
    }

    private static PassengerRouteSnapshot snapshot(Instant generatedAt, Instant expiresAt) {
        var a = new PassengerRouteSnapshot.GeoPoint(120.1, 30.2);
        var b = new PassengerRouteSnapshot.GeoPoint(120.2, 30.3);
        return new PassengerRouteSnapshot(UUID.randomUUID().toString(), 42L, "AIC-1", 3L,
                new PassengerRouteSnapshot.Place(null, "起点", "地址A", "杭州", a),
                new PassengerRouteSnapshot.Place(null, "终点", "地址B", "杭州", b),
                "gaode", "GCJ02", "DRIVING", PassengerRouteSnapshot.RouteKind.DEFAULT,
                List.of(a, b), List.of(new PassengerRouteSnapshot.NavigationStep(
                "沿路行驶", "直行", "", "东", "测试路", List.of(a, b))),
                10000, 900, generatedAt, expiresAt, null, List.of(), null);
    }
}
