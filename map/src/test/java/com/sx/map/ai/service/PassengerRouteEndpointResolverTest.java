package com.sx.map.ai.service;

import com.sx.map.ai.service.PassengerRouteEndpointResolver.Resolution;
import com.sx.map.ai.service.PassengerRouteEndpointResolver.Status;
import com.sx.map.ai.service.PassengerRoutePartialEndpointCache.EndpointRole;
import com.sx.map.ai.model.PassengerRouteSnapshot;
import com.sx.map.exception.AmapRouteEvidenceInsufficientException;
import com.sx.map.exception.AiRouteBusinessException;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.model.dto.RouteResponse;
import com.sx.map.service.AmapDrivingRouteService;
import com.sx.map.service.AmapPoiSearchService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PassengerRouteEndpointResolverTest {
    private final AmapPoiSearchService poiSearchService = mock(AmapPoiSearchService.class);
    private final PassengerRouteEndpointResolver resolver = new PassengerRouteEndpointResolver(poiSearchService);

    @Test
    void ambiguousNamesKeepSeparateTrustedPlacesForLaterSelection() {
        when(poiSearchService.search("杭州东站", null, null)).thenReturn(List.of(
                poi("P-HZ", "杭州东站", "杭州", "天城路1号", 120.1, 30.2),
                poi("P-NB", "杭州东站", "宁波", "东站路2号", 121.1, 29.9)));

        Resolution result = resolver.resolve("杭州东站", null);

        assertThat(result.status()).isEqualTo(Status.NEEDS_CLARIFICATION);
        assertThat(result.place()).isNull();
        assertThat(result.candidates()).extracting(PassengerRouteEndpointResolver.CandidateSummary::city)
                .containsExactly("杭州", "宁波");
        assertThat(result.selectableCandidates()).extracting(choice -> choice.place().placeId())
                .containsExactly("P-HZ", "P-NB");
        assertThat(result.selectableCandidates()).extracting(choice -> choice.place().coordinate().longitude())
                .containsExactly(120.1, 121.1);
        assertThat(result.selectableCandidates()).extracting(choice -> choice.summary().address())
                .containsExactly("天城路1号", "东站路2号");
        verify(poiSearchService).search("杭州东站", null, null);
    }

    @Test
    void fullFirstPageDoesNotSilentlyChooseItsSingleExactName() {
        List<AmapPoiCandidate> results = new ArrayList<>();
        results.add(poi("P-EXACT", "德清", "湖州", "德清县", 120.0, 30.0));
        for (int index = 1; index < 10; index++) {
            results.add(poi("P-" + index, "德清候选" + index, "湖州", "地址" + index,
                    120.0 + index / 100.0, 30.0));
        }
        when(poiSearchService.search("德清", "湖州", null)).thenReturn(results);

        Resolution result = resolver.resolve("德清", "湖州");

        assertThat(result.status()).isEqualTo(Status.INCOMPLETE_RESULTS);
        assertThat(result.place()).isNull();
        assertThat(result.selectableCandidates()).hasSize(10);
        assertThat(result.selectableCandidates().getFirst().place().placeId()).isEqualTo("P-EXACT");
    }

    @Test
    void invalidCoordinatesAreNotSelectableOrTreatedAsUnique() {
        when(poiSearchService.search("杭州东站", null, null)).thenReturn(List.of(
                poi("P-BAD", "杭州东站", "杭州", "无效地址", 999.0, 30.2),
                poi("P-GOOD", "杭州东站", "杭州", "有效地址", 120.1, 30.2)));

        Resolution result = resolver.resolve("杭州东站", null);

        assertThat(result.status()).isEqualTo(Status.NEEDS_CLARIFICATION);
        assertThat(result.place()).isNull();
        assertThat(result.selectableCandidates()).singleElement()
                .satisfies(choice -> assertThat(choice.place().placeId()).isEqualTo("P-GOOD"));
    }

    @Test
    void uniqueExactNameStillReturnsTrustedPlaceWithoutSelection() {
        when(poiSearchService.search("杭州东站", null, null)).thenReturn(List.of(
                poi("P-HZ", "杭州东站", "杭州", "天城路1号", 120.1, 30.2),
                poi("P-OTHER", "杭州西站", "杭州", "良睦路", 120.0, 30.3)));

        Resolution result = resolver.resolve("杭州东站", null);

        assertThat(result.status()).isEqualTo(Status.UNIQUE);
        assertThat(result.place().placeId()).isEqualTo("P-HZ");
        assertThat(result.selectableCandidates()).isEmpty();
    }

    @Test
    void originFiltersHighwayExitsWithoutReplacingAnExplicitExitName() {
        AmapPoiCandidate exit = poi("P-EXIT", "德清收费站(G25长深高速出口)", "湖州", "德清", 120, 30);
        exit.setType("交通设施;高速路出口");
        AmapPoiCandidate entrance = poi("P-ENTRY", "德清收费站(G25长深高速入口)", "湖州", "德清", 120.001, 30);
        entrance.setType("交通设施;高速路入口");
        when(poiSearchService.search("德清收费站", null, null)).thenReturn(List.of(exit, entrance));
        when(poiSearchService.search(exit.getName(), null, null)).thenReturn(List.of(exit, entrance));

        Resolution candidates = resolver.resolve("德清收费站", null, EndpointRole.ORIGIN);
        Resolution explicitExit = resolver.resolve(exit.getName(), null, EndpointRole.ORIGIN);

        assertThat(candidates.candidates()).extracting(PassengerRouteEndpointResolver.CandidateSummary::name)
                .containsExactly(entrance.getName());
        assertThat(candidates.selectableCandidates()).singleElement()
                .satisfies(choice -> assertThat(choice.place().placeId()).isEqualTo("P-ENTRY"));
        assertThat(explicitExit.status()).isEqualTo(Status.NOT_FOUND);
        assertThat(explicitExit.selectableCandidates()).isEmpty();
    }

    @Test
    void destinationFiltersHighwayEntrancesAndKeepsOrdinaryVenueEntrance() {
        AmapPoiCandidate entrance = poi("P-ENTRY", "莫干山收费站入口", "湖州", "德清", 120, 30);
        entrance.setType("高速路入口");
        AmapPoiCandidate exit = poi("P-EXIT", "莫干山收费站出口", "湖州", "德清", 120.001, 30);
        exit.setType("高速路出口");
        AmapPoiCandidate venue = poi("P-VENUE", "西溪湿地入口", "杭州", "西溪", 120.1, 30);
        venue.setType("风景名胜");
        when(poiSearchService.search("莫干山", null, null)).thenReturn(List.of(entrance, exit));
        when(poiSearchService.search(venue.getName(), null, null)).thenReturn(List.of(venue));

        assertThat(resolver.resolve("莫干山", null, EndpointRole.DESTINATION).candidates())
                .extracting(PassengerRouteEndpointResolver.CandidateSummary::name)
                .containsExactly(exit.getName());
        assertThat(resolver.resolve(venue.getName(), null, EndpointRole.DESTINATION).status())
                .isEqualTo(Status.UNIQUE);
    }

    @Test
    void highwayAccessChoicesDiscardOrdinaryIntersectionsAndDeduplicatePoiIds() {
        AmapPoiCandidate lakeEntrance = poi("P-LAKE", "莫干山收费站入口(湖州方向)",
                "湖州", "德清", 120, 30);
        lakeEntrance.setType("地名地址信息;交通地名;高速路入口");
        AmapPoiCandidate anotherEntrance = poi("P-OTHER", "莫干山收费站入口(黄山方向)",
                "湖州", "德清", 120.001, 30);
        anotherEntrance.setType("地名地址信息;交通地名;高速路入口");
        AmapPoiCandidate duplicateEntrance = poi("P-COPY", "莫干山收费站入口(湖州方向)",
                "湖州", "德清", 120, 30);
        duplicateEntrance.setType("地名地址信息;交通地名;高速路入口");
        AmapPoiCandidate intersection = poi("P-CROSS", "服务区与杭州第二绕城高速交叉口",
                "湖州", "德清", 120.002, 30);
        intersection.setType("地名地址信息;交通地名;路口名");
        AmapPoiCandidate remoteEntrance = poi("P-REMOTE", "其他收费站入口",
                "杭州", "余杭", 120.004, 30);
        remoteEntrance.setType("地名地址信息;交通地名;高速路入口");
        AmapPoiCandidate subway = poi("P-SUBWAY", "德清高速路口地铁站",
                "湖州", "德清", 120.003, 30);
        subway.setType("交通设施;地铁站");
        when(poiSearchService.search("德清高速路口", null, null)).thenReturn(List.of(
                remoteEntrance, intersection, lakeEntrance, lakeEntrance,
                duplicateEntrance, anotherEntrance, subway));

        Resolution result = resolver.resolve("德清高速路口", null, EndpointRole.ORIGIN);

        assertThat(result.status()).isEqualTo(Status.NEEDS_CLARIFICATION);
        assertThat(result.selectableCandidates()).extracting(choice -> choice.place().placeId())
                .containsExactly("P-LAKE", "P-OTHER", "P-REMOTE");
        assertThat(result.candidates()).extracting(PassengerRouteEndpointResolver.CandidateSummary::name)
                .doesNotContain(intersection.getName(), subway.getName());
    }

    @Test
    void broadScenicNameShowsFocusedChoicesButKeepsExplicitSubplaceSearch() {
        List<AmapPoiCandidate> results = List.of(
                poi("P-METRO", "西溪湿地北(地铁站)", "杭州", "文二西路", 120.01, 30),
                poi("P-TICKET", "西溪国家湿地公园售票处", "杭州", "景区内", 120.02, 30),
                poi("P-POND", "西溪国家湿地公园水塘", "杭州", "景区内", 120.03, 30),
                poi("P-TRIP", "西溪国家湿地公园洪园奇妙游", "杭州", "景区内", 120.04, 30),
                poi("P-PARK", "西溪国家湿地公园", "杭州", "天目山路", 120.05, 30),
                poi("P-HONG", "西溪国家湿地公园洪园", "杭州", "天目山路", 120.06, 30),
                poi("P-EAST", "西溪国家湿地公园东区", "杭州", "天目山路", 120.07, 30),
                poi("P-GATE", "西溪国家湿地公园入口", "杭州", "天目山路", 120.08, 30),
                poi("P-SHENTAN", "西溪国家湿地公园深潭口", "杭州", "天目山路", 120.09, 30),
                poi("P-TREE", "西溪国家湿地公园大樟树", "杭州", "景区内", 120.10, 30));
        when(poiSearchService.search("西溪湿地", null, null)).thenReturn(results);
        when(poiSearchService.search("西溪国家湿地公园售票处", null, null))
                .thenReturn(List.of(results.get(1)));

        Resolution broad = resolver.resolve("西溪湿地", null, EndpointRole.DESTINATION);
        Resolution explicit = resolver.resolve("西溪国家湿地公园售票处", null, EndpointRole.DESTINATION);

        assertThat(broad.status()).isEqualTo(Status.INCOMPLETE_RESULTS);
        assertThat(broad.selectableCandidates()).hasSize(5);
        assertThat(broad.selectableCandidates().getFirst().place().placeId()).isEqualTo("P-PARK");
        assertThat(broad.candidates()).extracting(PassengerRouteEndpointResolver.CandidateSummary::name)
                .doesNotContain("西溪湿地北(地铁站)", "西溪国家湿地公园售票处", "西溪国家湿地公园水塘");
        assertThat(explicit.status()).isEqualTo(Status.UNIQUE);
        assertThat(explicit.place().placeId()).isEqualTo("P-TICKET");
    }

    @Test
    void tollEndpointAcceptsMatchingArrivalOrNearEndpointOrderedTransition() {
        PassengerRouteSnapshot.Place station = new PassengerRouteSnapshot.Place(
                "P-ENTRY", "德清收费站(G25长深高速入口)", "德清", "湖州",
                new PassengerRouteSnapshot.GeoPoint(120.001, 30));
        PassengerRouteSnapshot.Place ordinary = new PassengerRouteSnapshot.Place(
                "P-DEST", "宁波站", "宁波", "宁波", new PassengerRouteSnapshot.GeoPoint(121.5, 29.9));
        RouteResponse route = new RouteResponse();
        route.setPolyline(List.of(point(120, 30), point(120.001, 30), point(120.015, 30)));
        var before = step("驶入德清互通", "", "地面道路", 120, 120.0005);
        var arrival = step("到达德清收费站", "到达收费站", "德清互通", 120.0005, 120.001);
        var after = step("进入G25长深高速", "", "G25长深高速", 120.001, 120.015);

        PassengerRouteEndpointDirectionValidator.validate(station, ordinary,
                new AmapDrivingRouteService.DrivingRouteOption(route, List.of(before, arrival, after)));
        PassengerRouteEndpointDirectionValidator.validate(station, ordinary,
                new AmapDrivingRouteService.DrivingRouteOption(route, List.of(before, after)));
        assertThatThrownBy(() -> PassengerRouteEndpointDirectionValidator.validate(station, ordinary,
                new AmapDrivingRouteService.DrivingRouteOption(route, List.of(after))))
                .isInstanceOf(AmapRouteEvidenceInsufficientException.class);
        assertThatThrownBy(() -> PassengerRouteEndpointDirectionValidator.validate(station, ordinary,
                new AmapDrivingRouteService.DrivingRouteOption(route, List.of(after, arrival, before))))
                .isInstanceOf(AmapRouteEvidenceInsufficientException.class);
    }

    @Test
    void distantHighwayEntryCannotBeAttributedToSelectedOriginStation() {
        PassengerRouteSnapshot.Place station = new PassengerRouteSnapshot.Place(
                "P-ENTRY", "德清收费站入口", "德清", "湖州",
                new PassengerRouteSnapshot.GeoPoint(120, 30));
        PassengerRouteSnapshot.Place destination = new PassengerRouteSnapshot.Place(
                "P-DEST", "宁波站", "宁波", "宁波", new PassengerRouteSnapshot.GeoPoint(121.5, 29.9));
        RouteResponse route = new RouteResponse();
        route.setPolyline(List.of(point(120, 30), point(120.03, 30)));
        var ordinaryRoad = step("沿地面道路行驶", "", "地面道路", 120, 120.02);
        var distantEntry = step("进入高速", "", "G25长深高速", 120.02, 120.03);

        assertThatThrownBy(() -> PassengerRouteEndpointDirectionValidator.validate(station, destination,
                new AmapDrivingRouteService.DrivingRouteOption(route, List.of(ordinaryRoad, distantEntry))))
                .isInstanceOf(AmapRouteEvidenceInsufficientException.class);
    }

    @Test
    void explicitHighwayEntryAtSelectedOriginCanReplaceMissingArrivalEvent() {
        PassengerRouteSnapshot.Place station = new PassengerRouteSnapshot.Place(
                "P-ENTRY", "莫干山收费站入口(湖州方向)", "德清", "湖州",
                new PassengerRouteSnapshot.GeoPoint(120, 30));
        PassengerRouteSnapshot.Place destination = new PassengerRouteSnapshot.Place(
                "P-DEST", "西溪国家湿地公园洪园", "杭州", "杭州",
                new PassengerRouteSnapshot.GeoPoint(120.1, 30.2));
        RouteResponse route = new RouteResponse();
        route.setPolyline(List.of(point(120, 30), point(120.01, 30)));
        var entry = step("进入高速", "进入高速", "G25长深高速", 120, 120.01);

        PassengerRouteEndpointDirectionValidator.validate(station, destination,
                new AmapDrivingRouteService.DrivingRouteOption(route, List.of(entry)));
    }

    @Test
    void selectedTollEntranceCanStartOnNamedRampMergedWithSameHighway() {
        PassengerRouteSnapshot.Place station = new PassengerRouteSnapshot.Place(
                "P-ENTRY", "莫干山收费站入口(湖州方向)", "德清", "湖州",
                new PassengerRouteSnapshot.GeoPoint(120, 30));
        PassengerRouteSnapshot.Place destination = new PassengerRouteSnapshot.Place(
                "P-DEST", "西溪国家湿地公园", "杭州", "杭州",
                new PassengerRouteSnapshot.GeoPoint(120.1, 30.2));
        RouteResponse route = new RouteResponse();
        route.setPolyline(List.of(point(120, 30), point(120.01, 30)));
        var merged = step("沿S43杭州绕城西复线入口途径S43杭州绕城西复线向东行驶", "直行",
                "S43杭州绕城西复线入口", 120, 120.01);
        PassengerRouteEndpointDirectionValidator.validate(station, destination,
                new AmapDrivingRouteService.DrivingRouteOption(route, List.of(merged)));

        var mainline = step("沿S43杭州绕城西复线向东行驶", "直行",
                "S43杭州绕城西复线", 120, 120.01);
        assertThatThrownBy(() -> PassengerRouteEndpointDirectionValidator.validate(station, destination,
                new AmapDrivingRouteService.DrivingRouteOption(route, List.of(mainline))))
                .isInstanceOf(AmapRouteEvidenceInsufficientException.class);
        var rampWithoutJoin = step("沿S43杭州绕城西复线入口向东行驶", "直行",
                "S43杭州绕城西复线入口", 120, 120.01);
        assertThatThrownBy(() -> PassengerRouteEndpointDirectionValidator.validate(station, destination,
                new AmapDrivingRouteService.DrivingRouteOption(route, List.of(rampWithoutJoin))))
                .isInstanceOf(AmapRouteEvidenceInsufficientException.class);
    }

    @Test
    void laterReturnToSameTollStationCannotProveOriginEntry() {
        PassengerRouteSnapshot.Place station = new PassengerRouteSnapshot.Place(
                "P-ENTRY", "德清收费站入口", "德清", "湖州",
                new PassengerRouteSnapshot.GeoPoint(120, 30));
        PassengerRouteSnapshot.Place destination = new PassengerRouteSnapshot.Place(
                "P-DEST", "宁波站", "宁波", "宁波", new PassengerRouteSnapshot.GeoPoint(121.5, 29.9));
        RouteResponse route = new RouteResponse();
        route.setPolyline(List.of(point(120, 30), point(120.02, 30), point(120, 30), point(120.01, 30)));
        var detour = step("沿地面道路绕行", "", "地面道路", 120, 120.02);
        var returnRoad = step("返回德清互通", "", "地面道路", 120.02, 119.9995);
        var arrival = step("到达德清收费站", "到达收费站", "德清互通", 119.9995, 120);
        var highway = step("沿G25高速行驶", "", "G25高速", 120, 120.01);

        assertThatThrownBy(() -> PassengerRouteEndpointDirectionValidator.validate(station, destination,
                new AmapDrivingRouteService.DrivingRouteOption(route,
                        List.of(detour, returnRoad, arrival, highway))))
                .isInstanceOf(AmapRouteEvidenceInsufficientException.class);
    }

    @Test
    void alreadyOnHighwayBeforeNearbyTransitionIsNotNewEntryFromOrigin() {
        PassengerRouteSnapshot.Place station = new PassengerRouteSnapshot.Place(
                "P-ENTRY", "德清收费站入口", "德清", "湖州",
                new PassengerRouteSnapshot.GeoPoint(120, 30));
        PassengerRouteSnapshot.Place destination = new PassengerRouteSnapshot.Place(
                "P-DEST", "宁波站", "宁波", "宁波", new PassengerRouteSnapshot.GeoPoint(121.5, 29.9));
        RouteResponse route = new RouteResponse();
        route.setPolyline(List.of(point(120, 30), point(120.0005, 30), point(120.001, 30)));
        var firstHighway = step("沿G25高速", "", "G25高速", 120, 120.0003);
        var serviceRoad = step("沿辅路行驶", "", "辅路", 120.0003, 120.0005);
        var secondHighway = step("并入G25高速", "", "G25高速", 120.0005, 120.001);

        assertThatThrownBy(() -> PassengerRouteEndpointDirectionValidator.validate(station, destination,
                new AmapDrivingRouteService.DrivingRouteOption(route,
                        List.of(firstHighway, serviceRoad, secondHighway))))
                .isInstanceOf(AmapRouteEvidenceInsufficientException.class);
    }

    @Test
    void destinationTollExitRequiresHighwayToOrdinaryRoadTransition() {
        PassengerRouteSnapshot.Place origin = new PassengerRouteSnapshot.Place(
                "P-ORIGIN", "宁波站", "宁波", "宁波", new PassengerRouteSnapshot.GeoPoint(120, 30));
        PassengerRouteSnapshot.Place exit = new PassengerRouteSnapshot.Place(
                "P-EXIT", "德清收费站(G25长深高速出口)", "德清", "湖州",
                new PassengerRouteSnapshot.GeoPoint(120.001, 30));
        RouteResponse route = new RouteResponse();
        route.setPolyline(List.of(point(120, 30), point(120.001, 30), point(120.0015, 30)));
        var before = step("沿G25长深高速", "", "G25长深高速", 120, 120.0005);
        var arrival = step("到达德清收费站", "到达收费站", "德清互通", 120.0005, 120.001);
        var after = step("驶出德清互通", "", "地面道路", 120.001, 120.0015);

        PassengerRouteEndpointDirectionValidator.validate(origin, exit,
                new AmapDrivingRouteService.DrivingRouteOption(route, List.of(before, arrival, after)));
        assertThatThrownBy(() -> PassengerRouteEndpointDirectionValidator.validate(origin, exit,
                new AmapDrivingRouteService.DrivingRouteOption(route, List.of(after, arrival, before))))
                .isInstanceOf(AmapRouteEvidenceInsufficientException.class);

        RouteResponse endingAtExit = new RouteResponse();
        endingAtExit.setPolyline(List.of(point(120, 30), point(120.001, 30)));
        var highway = step("沿G25长深高速", "", "G25长深高速", 120, 120.0005);
        var ordinary = step("驶向收费站出口", "", "地面道路", 120.0005, 120.001);
        PassengerRouteEndpointDirectionValidator.validate(origin, exit,
                new AmapDrivingRouteService.DrivingRouteOption(endingAtExit, List.of(highway, ordinary)));
    }

    @Test
    void defaultRouteDoesNotCacheUnprovenTollEntranceAsSuccessfulCard() {
        PassengerRouteConditionsCache conditions = mock(PassengerRouteConditionsCache.class);
        PassengerRouteSnapshotCache snapshots = mock(PassengerRouteSnapshotCache.class);
        AmapDrivingRouteService driving = mock(AmapDrivingRouteService.class);
        PassengerRouteSnapshot.Place station = new PassengerRouteSnapshot.Place(
                "P-ENTRY", "德清收费站(G25高速入口)", "德清", "湖州",
                new PassengerRouteSnapshot.GeoPoint(120.001, 30));
        PassengerRouteSnapshot.Place destination = new PassengerRouteSnapshot.Place(
                "P-DEST", "宁波站", "宁波", "宁波", new PassengerRouteSnapshot.GeoPoint(121.5, 29.9));
        Instant now = Instant.now();
        when(conditions.find(42L, "AIC-1", 7L)).thenReturn(Optional.of(
                new PassengerRouteConditionsCache.ConfirmedConditions(42L, "AIC-1", 7L,
                        station, destination, now, now.plusSeconds(300))));
        RouteResponse route = new RouteResponse();
        route.setProvider("gaode");
        route.setDistanceMeters(2000L);
        route.setDurationSeconds(300L);
        route.setPolyline(List.of(point(120, 30), point(120.015, 30)));
        when(driving.drivingRoutes(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                new AmapDrivingRouteService.DrivingRouteOption(route,
                        List.of(step("进入G25高速", "", "G25高速", 120, 120.015)))));

        PassengerDefaultRouteService service = new PassengerDefaultRouteService(driving, conditions, snapshots);
        assertThatThrownBy(() -> service.createAndCache(42L, "AIC-1", 7L))
                .isInstanceOfSatisfying(AiRouteBusinessException.class, error ->
                        assertThat(error.getStableCode()).isEqualTo("AI_ROUTE_UNVERIFIABLE"));
        verify(snapshots, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private static AmapDrivingRouteService.NavigationStep step(String instruction, String action,
                                                               String road, double start, double end) {
        return new AmapDrivingRouteService.NavigationStep(instruction, action, action, "", road,
                List.of(point(start, 30), point(end, 30)));
    }

    private static Point point(double longitude, double latitude) {
        Point point = new Point();
        point.setLng(longitude);
        point.setLat(latitude);
        return point;
    }

    private static AmapPoiCandidate poi(String id, String name, String city, String address,
                                        double longitude, double latitude) {
        AmapPoiCandidate candidate = new AmapPoiCandidate();
        candidate.setPoiId(id);
        candidate.setName(name);
        candidate.setCity(city);
        candidate.setAddress(address);
        candidate.setType("交通设施");
        Point center = new Point();
        center.setLng(longitude);
        center.setLat(latitude);
        candidate.setCenterPoint(center);
        return candidate;
    }
}
