package com.sx.map.ai.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PassengerRouteSnapshotTest {
    private static final Instant GENERATED_AT = Instant.parse("2026-09-17T02:00:00Z");
    private static final Instant EXPIRES_AT = GENERATED_AT.plusSeconds(300);
    private static final PassengerRouteSnapshot.GeoPoint START =
            new PassengerRouteSnapshot.GeoPoint(120.1, 30.2);
    private static final PassengerRouteSnapshot.GeoPoint END =
            new PassengerRouteSnapshot.GeoPoint(120.2, 30.3);

    @Test
    void retainsCompleteRouteWithoutAllowingSourceListsToChangeCachedFacts() {
        List<PassengerRouteSnapshot.GeoPoint> polyline = new ArrayList<>(List.of(START, END));
        List<PassengerRouteSnapshot.NavigationStep> steps = new ArrayList<>(List.of(
                new PassengerRouteSnapshot.NavigationStep("进入道路", "直行", "", "东", "测试路", polyline)
        ));

        PassengerRouteSnapshot snapshot = snapshot(PassengerRouteSnapshot.RouteKind.DEFAULT,
                polyline, steps, null, List.of(), null);
        polyline.clear();
        steps.clear();

        assertEquals(List.of(START, END), snapshot.polyline());
        assertEquals(List.of(START, END), snapshot.navigationSteps().getFirst().polyline());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.polyline().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.navigationSteps().clear());
    }

    @Test
    void rejectsIncompleteOrContradictoryRouteFacts() {
        List<PassengerRouteSnapshot.GeoPoint> line = List.of(START, END);
        List<PassengerRouteSnapshot.NavigationStep> steps = List.of(
                new PassengerRouteSnapshot.NavigationStep("进入道路", "直行", "", "东", "测试路", line)
        );
        assertThrows(IllegalArgumentException.class, () -> snapshot(
                PassengerRouteSnapshot.RouteKind.DEFAULT, List.of(START), steps, null, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> snapshot(
                PassengerRouteSnapshot.RouteKind.DEFAULT, line, List.of(), null, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> snapshot(
                PassengerRouteSnapshot.RouteKind.VIA_PLACE, line, steps, null, List.of(), null));

        PassengerRouteSnapshot.ViaPlace via = new PassengerRouteSnapshot.ViaPlace(
                place("测试收费站", START), PassengerRouteSnapshot.BusinessType.TOLL_STATION, List.of(START));
        PassengerRouteSnapshot.PassageVerification verification =
                new PassengerRouteSnapshot.PassageVerification("PASSED", "导航步骤到达收费站通道");
        assertThrows(IllegalArgumentException.class, () -> snapshot(
                PassengerRouteSnapshot.RouteKind.DEFAULT, line, steps, via, List.of(START), verification));
        assertThrows(IllegalArgumentException.class, () -> new PassengerRouteSnapshot.PassageVerification(
                "UNVERIFIABLE", "只有地理接近证据"));
    }

    @Test
    void keepsVerifiedWaypointAndOrderedRoutingPoints() {
        PassengerRouteSnapshot.GeoPoint entrance = new PassengerRouteSnapshot.GeoPoint(120.12, 30.21);
        PassengerRouteSnapshot.GeoPoint exit = new PassengerRouteSnapshot.GeoPoint(120.13, 30.22);
        PassengerRouteSnapshot.ViaPlace via = new PassengerRouteSnapshot.ViaPlace(
                place("测试服务区", entrance), PassengerRouteSnapshot.BusinessType.SERVICE_AREA,
                List.of(entrance, exit));
        List<PassengerRouteSnapshot.GeoPoint> routingPoints = new ArrayList<>(List.of(entrance, exit));
        PassengerRouteSnapshot snapshot = snapshot(PassengerRouteSnapshot.RouteKind.VIA_PLACE,
                List.of(START, END), List.of(new PassengerRouteSnapshot.NavigationStep(
                        "进入服务区", "进入", "", "东", "测试路", List.of(START, entrance, exit, END))),
                via, routingPoints,
                new PassengerRouteSnapshot.PassageVerification("PASSED", "按入口到出口顺序通过"));
        routingPoints.clear();

        assertEquals(List.of(entrance, exit), snapshot.routingWaypoints());
        assertEquals("PASSED", snapshot.passageVerification().verdict());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.routingWaypoints().clear());
    }

    private static PassengerRouteSnapshot snapshot(
            PassengerRouteSnapshot.RouteKind kind,
            List<PassengerRouteSnapshot.GeoPoint> polyline,
            List<PassengerRouteSnapshot.NavigationStep> steps,
            PassengerRouteSnapshot.ViaPlace via,
            List<PassengerRouteSnapshot.GeoPoint> routingWaypoints,
            PassengerRouteSnapshot.PassageVerification verification) {
        return new PassengerRouteSnapshot("RR-test", 42L, "conversation-test", 1L,
                place("起点", START), place("终点", END), "gaode", "GCJ02", "DRIVING",
                kind, polyline, steps, 12000L, 900L, GENERATED_AT, EXPIRES_AT,
                via, routingWaypoints, verification);
    }

    private static PassengerRouteSnapshot.Place place(String name, PassengerRouteSnapshot.GeoPoint coordinate) {
        return new PassengerRouteSnapshot.Place(null, name, "测试地址", "杭州", coordinate);
    }
}
