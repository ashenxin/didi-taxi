package com.sx.map.service;

import com.sx.map.ai.model.PassengerRouteSnapshot;
import com.sx.map.ai.model.CurrentRouteCheckMode;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.model.dto.RouteResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentRoutePassageServiceTest {

    private final CurrentRoutePassageService service =
            new CurrentRoutePassageService(new CurrentRouteRelationEvaluator(
                    new WaypointRouteCandidateEvaluator(),
                    new CurrentRouteSemanticConsistencyEvaluator()));

    @Test
    void checksTheSuppliedRouteInsteadOfWhetherAnotherRouteCanReachTheToll() {
        AmapPoiCandidate wuchang = toll("五常收费站(G25长深高速出口千岛湖方向)",
                point(120.046785, 30.279236));
        Point arrival = point(120.046877, 30.279328);
        var wuchangArrival = step("五常互通", "沿五常互通到达收费站", "到达收费站",
                point(120.0468, 30.2800), arrival);
        var throughWuchang = option("gaode",
                List.of(point(120.020, 30.543), wuchangArrival.polyline().getFirst(), arrival,
                        point(120.061, 30.254)), wuchangArrival);

        Point nearMainline = point(120.046785, 30.279506);
        var mainline = step("G25长深高速", "沿G25长深高速继续行驶", "进入匝道",
                point(120.020, 30.543), nearMainline, point(120.048, 30.241));
        var otherToll = step("留下枢纽", "沿留下枢纽到达收费站", "到达收费站",
                point(120.048, 30.241), point(120.048964, 30.239406));
        var bypassWuchang = option("gaode",
                List.of(mainline.polyline().getFirst(), nearMainline,
                        otherToll.polyline().getFirst(), otherToll.polyline().getLast()), mainline, otherToll);

        assertThat(service.check(throughWuchang, wuchang).verdict())
                .isEqualTo(CurrentRoutePassageService.Verdict.PASSED);
        assertThat(service.check(bypassWuchang, wuchang).verdict())
                .isEqualTo(CurrentRoutePassageService.Verdict.UNVERIFIABLE);
        CurrentRoutePassageService.RelationCheck bypassRelation =
                service.checkRelation(bypassWuchang, wuchang);
        assertThat(bypassRelation.verdict()).isEqualTo(CurrentRoutePassageService.Verdict.PASSED);
        assertThat(bypassRelation.mode()).isEqualTo(CurrentRouteCheckMode.PASS_NEARBY);
        assertThat(bypassRelation.reason()).isEqualTo("MAINLINE_BYPASSES_TOLL_STATION");
        assertThat(service.check(bypassWuchang, wuchang, CurrentRouteCheckMode.PASS_NEARBY).verdict())
                .isEqualTo(CurrentRoutePassageService.Verdict.PASSED);
        assertThat(service.check(bypassWuchang, wuchang, CurrentRouteCheckMode.USE_EXIT).verdict())
                .isEqualTo(CurrentRoutePassageService.Verdict.UNVERIFIABLE);
    }

    @Test
    void mockRouteAndMissingNavigationStepsCannotProduceAClaim() {
        AmapPoiCandidate wuchang = toll("五常收费站", point(120.046785, 30.279236));
        List<Point> polyline = List.of(point(120.046, 30.280), point(120.047, 30.278));

        assertThat(service.check(option("LOCAL_MOCK_ROUTE", polyline), wuchang).verdict())
                .isEqualTo(CurrentRoutePassageService.Verdict.UNVERIFIABLE);
        assertThat(service.check(option("gaode", polyline), wuchang).verdict())
                .isEqualTo(CurrentRoutePassageService.Verdict.UNVERIFIABLE);
        assertThat(service.check(option("gaode", polyline), null).verdict())
                .isEqualTo(CurrentRoutePassageService.Verdict.UNVERIFIABLE);
    }

    @Test
    void serviceAreaUsesItsNavigableEntranceNotItsPoiCenter() {
        AmapPoiCandidate serviceArea = new AmapPoiCandidate();
        serviceArea.setTypeCode("180300");
        serviceArea.setName("下沙服务区");
        serviceArea.setCenterPoint(point(120.10, 30.00));
        Point entrance = point(120.09, 30.00);
        serviceArea.setEntrancePoint(entrance);
        var arrival = step("服务区匝道", "到达途经地", "到达途经地",
                point(120.08, 30.00), entrance);

        var currentRoute = option("gaode",
                List.of(point(120.08, 30.00), entrance, point(120.12, 30.00)), arrival);
        assertThat(service.check(currentRoute, serviceArea).verdict())
                .isEqualTo(CurrentRoutePassageService.Verdict.PASSED);

        serviceArea.setEntrancePoint(null);
        assertThat(service.check(currentRoute, serviceArea).verdict())
                .isEqualTo(CurrentRoutePassageService.Verdict.UNVERIFIABLE);
    }

    @Test
    void checksCurrentPassengerRouteSnapshotWithoutReplanning() {
        AmapPoiCandidate wuchang = toll("五常收费站(G25长深高速出口千岛湖方向)",
                point(120.046785, 30.279236));
        Point arrival = point(120.046877, 30.279328);
        PassengerRouteSnapshot snapshot = snapshot(
                List.of(point(120.020, 30.543), point(120.0468, 30.2800), arrival,
                        point(120.061, 30.254)),
                List.of(step("五常互通", "沿五常互通到达收费站", "到达收费站",
                        point(120.0468, 30.2800), arrival)));

        CurrentRoutePassageService.CheckResult result = service.check(snapshot, wuchang);

        assertThat(result.verdict()).isEqualTo(CurrentRoutePassageService.Verdict.PASSED);
        assertThat(result.reason()).isEqualTo("PLACE_ARRIVAL_MATCHED");
    }

    @Test
    void missingSnapshotCannotBePresentedAsCurrentRouteFact() {
        AmapPoiCandidate wuchang = toll("五常收费站", point(120.046785, 30.279236));

        CurrentRoutePassageService.CheckResult result = service.check(
                (PassengerRouteSnapshot) null, wuchang);

        assertThat(result.verdict()).isEqualTo(CurrentRoutePassageService.Verdict.UNVERIFIABLE);
        assertThat(result.reason()).isEqualTo("REAL_ROUTE_REQUIRED");
    }

    @Test
    void enteringAgainConflictsWhenRouteAlreadyRunsOnHighwayBeforeTarget() {
        AmapPoiCandidate nanxunWest = toll("南浔西收费站(S47湖杭高速入口)",
                point(120.302692, 30.813526));
        var deqingToll = step("德清互通", "沿德清互通到达收费站", "到达收费站",
                point(120.000, 30.550), point(120.005, 30.545));
        var g25 = step("G25长深高速", "沿G25长深高速向北行驶", "",
                point(120.005, 30.545), point(120.180, 30.700));
        var s47 = step("S47湖杭高速", "沿S47湖杭高速继续向北行驶", "进入匝道",
                point(120.180, 30.700), point(120.3027, 30.8135), point(120.360, 30.900));
        var route = option("gaode", List.of(
                deqingToll.polyline().getFirst(), deqingToll.polyline().getLast(),
                g25.polyline().getLast(), s47.polyline().get(1), s47.polyline().getLast()),
                deqingToll, g25, s47);

        CurrentRoutePassageService.CheckResult result = service.check(
                route, nanxunWest, CurrentRouteCheckMode.ENTER_HIGHWAY);

        assertThat(result.verdict()).isEqualTo(CurrentRoutePassageService.Verdict.CONFLICT);
        assertThat(result.reason()).isEqualTo("ALREADY_ON_HIGHWAY_BEFORE_TARGET");
        assertThat(result.detail()).contains("德清互通").contains("S47湖杭高速")
                .contains("不会再从该入口驶入高速");
    }

    @Test
    void matchingTargetArrivalRemainsPositiveEvidenceForEnteringHighway() {
        Point entrance = point(120.302692, 30.813526);
        AmapPoiCandidate nanxunWest = toll("南浔西收费站(S47湖杭高速入口)", entrance);
        var localRoad = step("湖浔大道", "沿湖浔大道行驶", "",
                point(120.280, 30.790), point(120.300, 30.810));
        var targetArrival = step("南浔西互通", "沿南浔西互通到达收费站", "到达收费站",
                point(120.300, 30.810), entrance);
        var highway = step("S47湖杭高速", "沿S47湖杭高速向北行驶", "",
                entrance, point(120.360, 30.900));
        var route = option("gaode", List.of(localRoad.polyline().getFirst(),
                targetArrival.polyline().getFirst(), entrance, highway.polyline().getLast()),
                localRoad, targetArrival, highway);

        CurrentRoutePassageService.CheckResult result = service.check(
                route, nanxunWest, CurrentRouteCheckMode.ENTER_HIGHWAY);

        assertThat(result.verdict()).isEqualTo(CurrentRoutePassageService.Verdict.PASSED);
        assertThat(result.reason()).isEqualTo("ENTRANCE_ARRIVAL_MATCHED");
    }

    @Test
    void leavingHighwayAtTollCannotBeMisreportedAsEnteringThroughNearbyEntrance() {
        Point tollPoint = point(120.046877, 30.279328);
        AmapPoiCandidate entrance = toll("五常收费站(G25长深高速入口北向)", tollPoint);
        var highway = step("紫金港枢纽", "沿紫金港枢纽途径G25长深高速向南行驶进入匝道", "进入匝道",
                point(120.053234, 30.338331), point(120.047392, 30.280991));
        var targetArrival = step("五常互通", "沿五常互通向南行驶到达收费站", "到达收费站",
                point(120.047392, 30.280991), tollPoint);
        var localRoad = step("五常互通", "沿五常互通向西南行驶进入主路", "进入主路",
                tollPoint, point(120.046368, 30.278126));
        var route = option("gaode", List.of(
                highway.polyline().getFirst(), highway.polyline().getLast(), tollPoint,
                localRoad.polyline().getLast()), highway, targetArrival, localRoad);

        CurrentRoutePassageService.CheckResult result = service.check(
                route, entrance, CurrentRouteCheckMode.ENTER_HIGHWAY);

        assertThat(result.verdict()).isEqualTo(CurrentRoutePassageService.Verdict.CONFLICT);
        assertThat(result.reason()).isEqualTo("TOLL_ACTION_DIRECTION_CONFLICT");
        assertThat(result.detail()).contains("驶出高速").contains("不是从该入口驶入");
    }

    @Test
    void highwayToLocalRoadTransitionProvesUsingTheMatchingExit() {
        Point tollPoint = point(120.046877, 30.279328);
        AmapPoiCandidate exit = toll("五常收费站(G25长深高速出口千岛湖方向)", tollPoint);
        var highway = step("G25长深高速", "沿G25长深高速向南行驶进入匝道", "进入匝道",
                point(120.053234, 30.338331), point(120.047392, 30.280991));
        var targetArrival = step("五常互通", "沿五常互通向南行驶到达收费站", "到达收费站",
                point(120.047392, 30.280991), tollPoint);
        var localRoad = step("五常互通", "沿五常互通向西南行驶进入主路", "进入主路",
                tollPoint, point(120.046368, 30.278126));
        var route = option("gaode", List.of(
                highway.polyline().getFirst(), highway.polyline().getLast(), tollPoint,
                localRoad.polyline().getLast()), highway, targetArrival, localRoad);

        CurrentRoutePassageService.CheckResult result = service.check(
                route, exit, CurrentRouteCheckMode.USE_EXIT);

        assertThat(result.verdict()).isEqualTo(CurrentRoutePassageService.Verdict.PASSED);
        assertThat(result.reason()).isEqualTo("EXIT_ARRIVAL_MATCHED");
    }

    @Test
    void earlierHighwayDoesNotConflictAfterRouteHasReturnedToLocalRoad() {
        AmapPoiCandidate nextEntrance = toll("练市收费站(S47湖杭高速入口)",
                point(120.420, 30.690));
        var earlierHighway = step("S47湖杭高速", "沿S47湖杭高速行驶", "",
                point(120.200, 30.800), point(120.300, 30.760));
        var localRoad = step("湖盐公路", "沿湖盐公路行驶", "",
                point(120.300, 30.760), point(120.419, 30.691));
        var entranceRamp = step("S47湖杭高速", "进入S47湖杭高速匝道", "进入匝道",
                point(120.419, 30.691), point(120.421, 30.689));
        var route = option("gaode", List.of(earlierHighway.polyline().getFirst(),
                earlierHighway.polyline().getLast(), localRoad.polyline().getLast(),
                entranceRamp.polyline().getLast()), earlierHighway, localRoad, entranceRamp);

        CurrentRoutePassageService.CheckResult result = service.check(
                route, nextEntrance, CurrentRouteCheckMode.ENTER_HIGHWAY);

        assertThat(result.verdict()).isEqualTo(CurrentRoutePassageService.Verdict.UNVERIFIABLE);
        assertThat(result.reason()).isEqualTo("ACTION_NOT_PROVEN");
    }

    @Test
    void entrancePoiCannotBeReusedAsAnExitTarget() {
        Point entrance = point(120.302692, 30.813526);
        AmapPoiCandidate nanxunWestEntrance = toll(
                "南浔西收费站(S47湖杭高速入口)", entrance);
        var arrival = step("南浔西互通", "沿南浔西互通到达收费站", "到达收费站",
                point(120.300, 30.810), entrance);
        var route = option("gaode", List.of(arrival.polyline().getFirst(), entrance), arrival);

        CurrentRoutePassageService.CheckResult result = service.check(
                route, nanxunWestEntrance, CurrentRouteCheckMode.USE_EXIT);

        assertThat(result.verdict()).isEqualTo(CurrentRoutePassageService.Verdict.CONFLICT);
        assertThat(result.reason()).isEqualTo("TARGET_ROLE_CONFLICT");
        assertThat(result.detail()).contains("高速入口").contains("不能作为驶出高速的出口核查");
    }

    @Test
    void relationPrefersProvenArrivalOverNearbyDistance() {
        AmapPoiCandidate wuchang = toll("五常收费站(G25长深高速出口千岛湖方向)",
                point(120.046785, 30.279236));
        Point arrival = point(120.046877, 30.279328);
        var arrivalStep = step("五常互通", "沿五常互通到达收费站", "到达收费站",
                point(120.0468, 30.2800), arrival);
        var route = option("gaode",
                List.of(point(120.020, 30.543), arrivalStep.polyline().getFirst(), arrival,
                        point(120.061, 30.254)), arrivalStep);

        CurrentRoutePassageService.RelationCheck result = service.checkRelation(route, wuchang);

        assertThat(result.verdict()).isEqualTo(CurrentRoutePassageService.Verdict.PASSED);
        assertThat(result.mode()).isEqualTo(CurrentRouteCheckMode.VISIT_PLACE);
        assertThat(result.reason()).isEqualTo("PLACE_ARRIVAL_MATCHED");
    }

    @Test
    void relationReportsNearbyOnlyWhenArrivalIsNotProven() {
        AmapPoiCandidate wuchang = toll("五常收费站", point(120.046785, 30.279236));
        Point start = point(120.040, 30.279);
        Point end = point(120.054, 30.279);
        var road = step("文一西路", "沿文一西路行驶", "", start, end);
        var route = option("gaode", List.of(start, end), road);

        CurrentRoutePassageService.RelationCheck result = service.checkRelation(route, wuchang);

        assertThat(result.verdict()).isEqualTo(CurrentRoutePassageService.Verdict.PASSED);
        assertThat(result.mode()).isEqualTo(CurrentRouteCheckMode.PASS_NEARBY);
        assertThat(result.reason()).isEqualTo("ROUTE_PASSES_NEARBY_ONLY");
        assertThat(result.detail()).contains("没有证据表明实际通过");
    }

    @Test
    void relationReportsNotOnRouteWhenClearlyFarAway() {
        AmapPoiCandidate wuchang = toll("五常收费站", point(120.046785, 30.279236));
        Point start = point(120.200, 30.400);
        Point end = point(120.300, 30.500);
        var road = step("沪杭高速", "沿沪杭高速行驶", "", start, end);
        var route = option("gaode", List.of(start, end), road);

        CurrentRoutePassageService.RelationCheck result = service.checkRelation(route, wuchang);

        assertThat(result.verdict()).isEqualTo(CurrentRoutePassageService.Verdict.NOT_PASSED);
        assertThat(result.mode()).isEqualTo(CurrentRouteCheckMode.PASS_NEARBY);
        assertThat(result.reason()).isEqualTo("ROUTE_MISSES_PLACE");
    }

    @Test
    void relationIsUnknownWithoutCompleteGeometry() {
        AmapPoiCandidate wuchang = toll("五常收费站", point(120.046785, 30.279236));
        var route = option("gaode", List.of());

        CurrentRoutePassageService.RelationCheck result = service.checkRelation(route, wuchang);

        assertThat(result.verdict()).isEqualTo(CurrentRoutePassageService.Verdict.UNVERIFIABLE);
        assertThat(result.mode()).isEqualTo(CurrentRouteCheckMode.PASS_NEARBY);
        assertThat(result.reason()).isEqualTo("ROUTE_GEOMETRY_MISSING");
    }

    private static AmapPoiCandidate toll(String name, Point center) {
        AmapPoiCandidate candidate = new AmapPoiCandidate();
        candidate.setName(name);
        candidate.setTypeCode("180200");
        candidate.setCenterPoint(center);
        return candidate;
    }

    private static AmapDrivingRouteService.DrivingRouteOption option(String provider,
                                                                     List<Point> polyline,
                                                                     AmapDrivingRouteService.NavigationStep... steps) {
        RouteResponse route = new RouteResponse();
        route.setProvider(provider);
        route.setPolyline(polyline);
        route.setDistanceMeters(50_000L);
        route.setDurationSeconds(3_600L);
        return new AmapDrivingRouteService.DrivingRouteOption(route, List.of(steps));
    }

    private static AmapDrivingRouteService.NavigationStep step(String road, String instruction,
                                                               String assistantAction, Point... polyline) {
        return new AmapDrivingRouteService.NavigationStep(instruction, "", assistantAction, "", road,
                List.of(polyline));
    }

    private static Point point(double longitude, double latitude) {
        Point point = new Point();
        point.setLng(longitude);
        point.setLat(latitude);
        return point;
    }

    private static PassengerRouteSnapshot snapshot(
            List<Point> polyline, List<AmapDrivingRouteService.NavigationStep> steps) {
        Instant generatedAt = Instant.parse("2026-09-19T01:00:00Z");
        return new PassengerRouteSnapshot(
                UUID.randomUUID().toString(), 10001L, "AIC-1", 1L,
                place("德清", 120.00, 30.50), place("杭州东站", 120.21, 30.29),
                "gaode", PassengerRouteSnapshot.COORDINATE_SYSTEM_GCJ02,
                PassengerRouteSnapshot.TRAVEL_MODE_DRIVING,
                PassengerRouteSnapshot.RouteKind.DEFAULT,
                polyline.stream().map(CurrentRoutePassageServiceTest::geoPoint).toList(),
                steps.stream().map(CurrentRoutePassageServiceTest::snapshotStep).toList(),
                50_000L, 3_600L, generatedAt, generatedAt.plusSeconds(300),
                null, List.of(), null);
    }

    private static PassengerRouteSnapshot.Place place(String name, double longitude, double latitude) {
        return new PassengerRouteSnapshot.Place(null, name, null, "浙江",
                new PassengerRouteSnapshot.GeoPoint(longitude, latitude));
    }

    private static PassengerRouteSnapshot.GeoPoint geoPoint(Point point) {
        return new PassengerRouteSnapshot.GeoPoint(point.getLng(), point.getLat());
    }

    private static PassengerRouteSnapshot.NavigationStep snapshotStep(
            AmapDrivingRouteService.NavigationStep step) {
        return new PassengerRouteSnapshot.NavigationStep(
                step.instruction(), step.action(), step.assistantAction(),
                step.orientation(), step.roadName(),
                step.polyline().stream().map(CurrentRoutePassageServiceTest::geoPoint).toList());
    }
}
