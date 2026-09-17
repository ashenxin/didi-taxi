package com.sx.map.service;

import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.model.dto.RouteResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentRoutePassageServiceTest {

    private final CurrentRoutePassageService service =
            new CurrentRoutePassageService(new WaypointRouteCandidateEvaluator());

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
                .isEqualTo(CurrentRoutePassageService.Verdict.NOT_PASSED);
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
}
