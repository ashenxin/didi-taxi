package com.sx.map.service;

import com.sx.map.model.dto.Point;
import com.sx.map.model.dto.RouteRequest;
import com.sx.map.model.dto.RouteResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockRouteProviderTest {

    private final MockRouteProvider provider = new MockRouteProvider(
            "mock-route-v1", 3_000L, 30_000L, 6.0d, 14.0d);

    @Test
    void sameInputAndVersionProducesSameMetrics() {
        RouteRequest request = request(30.1000000d, 120.1000000d, 30.2000000d, 120.2000000d);

        RouteResponse first = provider.route(request);
        RouteResponse second = provider.route(request);

        assertThat(second.getDistanceMeters()).isEqualTo(first.getDistanceMeters());
        assertThat(second.getDurationSeconds()).isEqualTo(first.getDurationSeconds());
        assertThat(second.getTraceId()).isEqualTo(first.getTraceId());
        assertThat(second.getProvider()).isEqualTo("LOCAL_MOCK_ROUTE");
        assertThat(second.getVersion()).isEqualTo("mock-route-v1");
        assertThat(second.getDistanceMeters()).isBetween(3_000L, 30_000L);
        assertThat(second.getDurationSeconds()).isPositive();
    }

    @Test
    void changingCoordinatesChangesStableSeed() {
        RouteResponse first = provider.route(request(30.1000000d, 120.1000000d, 30.2000000d, 120.2000000d));
        RouteResponse second = provider.route(request(30.1000000d, 120.1000000d, 30.3000000d, 120.3000000d));

        assertThat(second.getTraceId()).isNotEqualTo(first.getTraceId());
    }

    private static RouteRequest request(double originLat, double originLng, double destLat, double destLng) {
        Point origin = new Point();
        origin.setLat(originLat);
        origin.setLng(originLng);
        Point dest = new Point();
        dest.setLat(destLat);
        dest.setLng(destLng);
        RouteRequest request = new RouteRequest();
        request.setOrigin(origin);
        request.setDest(dest);
        return request;
    }
}
