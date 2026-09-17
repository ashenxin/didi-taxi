package com.sx.map.model.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PassengerRouteCandidateTest {

    /**
     * 路线生成后，算路坐标不能因外部集合增删而改变。这里保护的是集合结构；Point本身仍是现有DTO。
     */
    @Test
    void copiesRoutingWaypointCollectionAndExposesReadOnlyView() {
        Point entrance = point(120.10, 30.10);
        Point exit = point(120.11, 30.10);
        List<Point> source = new ArrayList<>(List.of(entrance, exit));

        PassengerRouteCandidate candidate = candidate(source, Instant.parse("2026-09-16T10:00:00Z"),
                Instant.parse("2026-09-16T10:05:00Z"));
        source.clear();

        assertThat(candidate.routingWaypoints()).containsExactly(entrance, exit);
        assertThatThrownBy(() -> candidate.routingWaypoints().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsExpiryThatIsNotAfterGenerationTime() {
        Instant generatedAt = Instant.parse("2026-09-16T10:00:00Z");

        assertThatThrownBy(() -> candidate(List.of(point(120.10, 30.10)), generatedAt, generatedAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("失效时间必须晚于生成时间");
    }

    @Test
    void rejectsPassageModeOutsidePublicContract() {
        Instant generatedAt = Instant.parse("2026-09-16T10:00:00Z");
        AmapPoiCandidate waypoint = new AmapPoiCandidate();
        waypoint.setPoiId("B0TEST001");
        RouteResponse route = new RouteResponse();

        assertThatThrownBy(() -> new PassengerRouteCandidate(
                "PRC-test",
                waypoint,
                List.of(point(120.10, 30.10)),
                "NAVIGATION_POINT",
                route,
                "SITE_ARRIVAL_MATCHED",
                generatedAt,
                generatedAt.plusSeconds(300)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的途经验证方式");
    }

    private static PassengerRouteCandidate candidate(List<Point> waypoints,
                                                     Instant generatedAt,
                                                     Instant expiresAt) {
        AmapPoiCandidate waypoint = new AmapPoiCandidate();
        waypoint.setPoiId("B0TEST001");
        waypoint.setName("下沙服务区");
        RouteResponse route = new RouteResponse();
        route.setDistanceMeters(50_000L);
        route.setDurationSeconds(3_600L);
        return new PassengerRouteCandidate(
                "PRC-test",
                waypoint,
                waypoints,
                PassengerRouteCandidate.PASSAGE_MODE_ENTER_AND_EXIT,
                route,
                "SITE_ARRIVAL_MATCHED",
                generatedAt,
                expiresAt
        );
    }

    private static Point point(double lng, double lat) {
        Point point = new Point();
        point.setLng(lng);
        point.setLat(lat);
        return point;
    }
}
