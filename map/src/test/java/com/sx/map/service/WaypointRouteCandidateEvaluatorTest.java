package com.sx.map.service;

import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.model.dto.RouteResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WaypointRouteCandidateEvaluatorTest {

    private final WaypointRouteCandidateEvaluator evaluator = new WaypointRouteCandidateEvaluator();

    @Test
    void acceptsWuchangOnlyWhenTollArrivalEndsAtMatchingInterchange() {
        AmapPoiCandidate wuchang = toll("五常收费站(G25长深高速出口千岛湖方向)", 120.046785, 30.279236);
        Point arrival = point(120.046877, 30.279328);
        AmapDrivingRouteService.NavigationStep tollStep = step("五常互通", "沿五常互通减速行驶到达收费站",
                "到达收费站", point(120.0468, 30.2800), arrival);

        var result = evaluator.evaluate(wuchang, List.of(), option(
                route(point(120.020, 30.543), tollStep.polyline().getFirst(), arrival, point(120.061, 30.254)),
                tollStep));

        assertThat(result.status()).isEqualTo(WaypointRouteCandidateEvaluator.Status.ACCEPTED);
        assertThat(result.reason()).isEqualTo(WaypointRouteCandidateEvaluator.Reason.TOLL_ARRIVAL_MATCHED);
    }

    @Test
    void doesNotAcceptMainlinePassingThirtyMetersFromWuchang() {
        AmapPoiCandidate wuchang = toll("五常收费站(G25长深高速出口千岛湖方向)", 120.046785, 30.279236);
        Point nearMainline = point(120.046785, 30.279506);
        Point liuxiaArrival = point(120.048964, 30.239406);
        var mainline = step("G25长深高速", "沿G25长深高速继续行驶", "进入匝道",
                point(120.020, 30.543), nearMainline, point(120.048, 30.241));
        var otherToll = step("留下枢纽", "沿留下枢纽到达收费站", "到达收费站",
                point(120.048, 30.241), liuxiaArrival);

        var result = evaluator.evaluate(wuchang, List.of(), option(
                route(mainline.polyline().getFirst(), nearMainline, otherToll.polyline().getFirst(), liuxiaArrival),
                mainline, otherToll));

        assertThat(result.status()).isEqualTo(WaypointRouteCandidateEvaluator.Status.REJECTED);
        assertThat(result.reason()).isEqualTo(WaypointRouteCandidateEvaluator.Reason.MAINLINE_BYPASSES_TOLL_STATION);
    }

    @Test
    void nearbyArrivalAtAnotherTollDoesNotProveStationIdentity() {
        AmapPoiCandidate wuchang = toll("五常收费站", 120.046785, 30.279236);
        Point arrival = point(120.046877, 30.279328);
        var wrongStation = step("其他互通", "沿其他互通到达收费站", "到达收费站",
                point(120.0468, 30.2800), arrival);

        var result = evaluator.evaluate(wuchang, List.of(), option(
                route(wrongStation.polyline().getFirst(), arrival, point(120.061, 30.254)), wrongStation));

        assertThat(result.status()).isEqualTo(WaypointRouteCandidateEvaluator.Status.AMBIGUOUS);
    }

    @Test
    void nearMainlineWithoutCompleteTollEvidenceRemainsUnknown() {
        AmapPoiCandidate wuchang = toll("五常收费站", 120.046785, 30.279236);
        var mainline = step("G25长深高速", "沿G25长深高速继续行驶", "",
                point(120.046, 30.280), point(120.047, 30.278));

        var result = evaluator.evaluate(wuchang, List.of(), option(
                route(mainline.polyline().getFirst(), mainline.polyline().getLast()), mainline));

        assertThat(result.status()).isEqualTo(WaypointRouteCandidateEvaluator.Status.AMBIGUOUS);
    }

    @Test
    void legalUturnDoesNotCancelVerifiedTollPassage() {
        AmapPoiCandidate wuchang = toll("五常收费站", 120.046785, 30.279236);
        Point arrival = point(120.046877, 30.279328);
        var tollStep = step("五常互通", "沿五常互通到达收费站", "到达收费站",
                point(120.0468, 30.2800), arrival);
        var uturn = step("文二西路", "沿文二西路左转调头", "", arrival, point(120.045, 30.275));

        var result = evaluator.evaluate(wuchang, List.of(), option(
                route(tollStep.polyline().getFirst(), arrival, uturn.polyline().getLast()), tollStep, uturn));

        assertThat(result.status()).isEqualTo(WaypointRouteCandidateEvaluator.Status.ACCEPTED);
    }

    @Test
    void serviceAreaRequiresArrivalAtNavigableEntranceAndSubsequentExit() {
        AmapPoiCandidate serviceArea = new AmapPoiCandidate();
        serviceArea.setName("下沙服务区");
        Point entrance = point(120.09, 30.00);
        Point exit = point(120.11, 30.00);
        var arrival = step("服务区匝道", "到达途经地", "到达途经地",
                point(120.08, 30.00), entrance);
        var leaving = step("服务区出口", "驶离服务区", "",
                entrance, exit, point(120.12, 30.00));

        var result = evaluator.evaluate(serviceArea, List.of(entrance, exit), option(
                route(point(120.08, 30.00), entrance, exit, point(120.12, 30.00)), arrival, leaving));

        assertThat(result.status()).isEqualTo(WaypointRouteCandidateEvaluator.Status.ACCEPTED);
        assertThat(result.reason()).isEqualTo(WaypointRouteCandidateEvaluator.Reason.SITE_ARRIVAL_MATCHED);
    }

    @Test
    void missingStepsOrGeometryCannotBeCalledVerified() {
        AmapPoiCandidate station = toll("五常收费站", 120.046785, 30.279236);
        var noSteps = evaluator.evaluate(station, List.of(), option(
                route(point(120.046, 30.280), point(120.047, 30.278))));
        RouteResponse noGeometry = new RouteResponse();
        noGeometry.setDistanceMeters(1_000L);
        noGeometry.setDurationSeconds(100L);
        var missingGeometry = evaluator.evaluate(station, List.of(), option(noGeometry,
                step("五常互通", "到达收费站", "到达收费站", point(120.046, 30.280), point(120.047, 30.278))));

        assertThat(noSteps.reason()).isEqualTo(WaypointRouteCandidateEvaluator.Reason.NAVIGATION_STEPS_MISSING);
        assertThat(missingGeometry.reason()).isEqualTo(WaypointRouteCandidateEvaluator.Reason.ROUTE_GEOMETRY_MISSING);
    }

    private static AmapPoiCandidate toll(String name, double lng, double lat) {
        AmapPoiCandidate station = new AmapPoiCandidate();
        station.setPoiId("B0TEST001");
        station.setName(name);
        station.setTypeCode("180200");
        station.setCenterPoint(point(lng, lat));
        return station;
    }

    private static AmapDrivingRouteService.NavigationStep step(String road, String instruction,
                                                               String assistantAction, Point... polyline) {
        return new AmapDrivingRouteService.NavigationStep(instruction, "", assistantAction, "", road,
                List.of(polyline));
    }

    private static AmapDrivingRouteService.DrivingRouteOption option(RouteResponse route,
                                                                     AmapDrivingRouteService.NavigationStep... steps) {
        return new AmapDrivingRouteService.DrivingRouteOption(route, List.of(steps));
    }

    private static RouteResponse route(Point... polyline) {
        RouteResponse result = new RouteResponse();
        result.setDistanceMeters(50_000L);
        result.setDurationSeconds(3_600L);
        result.setPolyline(List.of(polyline));
        return result;
    }

    private static Point point(double lng, double lat) {
        Point point = new Point();
        point.setLng(lng);
        point.setLat(lat);
        return point;
    }
}
