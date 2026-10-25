package com.sx.map.ai.service;

import com.sx.map.ai.model.CurrentRouteCheckMode;
import com.sx.map.ai.model.PassengerRouteIntent;
import com.sx.map.ai.model.PassengerRouteSnapshot;
import com.sx.map.ai.model.PassengerRouteSnapshot.BusinessType;
import com.sx.map.ai.model.PassengerRouteSnapshot.GeoPoint;
import com.sx.map.ai.model.PassengerRouteSnapshot.Place;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.Choice;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.ChoiceRole;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.ChoiceTask;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.PlaceCandidate;
import com.sx.map.ai.tool.PassengerRoutePoiTools;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.service.CurrentRoutePassageService;
import com.sx.map.service.CurrentRoutePassageService.RelationCheck;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PassengerCurrentRouteCheckServiceTest {

    private final PassengerRouteSnapshotCache snapshotCache = mock(PassengerRouteSnapshotCache.class);
    private final PassengerRoutePoiTools poiTools = mock(PassengerRoutePoiTools.class);
    private final PassengerRoutePlaceChoiceCache placeChoiceCache =
            mock(PassengerRoutePlaceChoiceCache.class);
    private final PassengerCurrentRouteClarificationCache clarificationCache =
            mock(PassengerCurrentRouteClarificationCache.class);
    private final CurrentRoutePassageService passageService = mock(CurrentRoutePassageService.class);

    private final PassengerCurrentRouteCheckService service =
            new PassengerCurrentRouteCheckService(snapshotCache, poiTools, placeChoiceCache,
                    clarificationCache, passageService);

    @Test
    void unspecifiedActionReturnsRelationInsteadOfAskingForMeaning() {
        PassengerRouteSnapshot snapshot = snapshot();
        when(snapshotCache.findForReplay("route-ref", 10001L, "AIC-1"))
                .thenReturn(Optional.of(snapshot));
        when(snapshotCache.find("route-ref", 10001L, "AIC-1", 3L))
                .thenReturn(Optional.of(snapshot));
        AmapPoiCandidate candidate = candidate("五常收费站");
        when(poiTools.searchCurrentRouteCheckCandidates("五常收费站", null))
                .thenReturn(List.of(candidate));
        when(poiTools.currentRouteCheckBusinessType(candidate)).thenReturn(BusinessType.TOLL_STATION);
        when(passageService.checkRelation(eq(snapshot), any())).thenReturn(
                new CurrentRoutePassageService.RelationCheck(
                        CurrentRoutePassageService.Verdict.PASSED,
                        CurrentRouteCheckMode.PASS_NEARBY,
                        "ROUTE_PASSES_NEARBY_ONLY",
                        "路线折线与目标地点最近约652米，但没有证据表明实际通过该地点"));

        PassengerCurrentRouteCheckService.Result result = service.check(
                10001L, "AIC-1", "route-ref", 4L, "五常收费站", null,
                CurrentRouteCheckMode.UNSPECIFIED, "这条路线经过五常收费站吗");

        assertThat(result.status()).isEqualTo(PassengerCurrentRouteCheckService.Status.CHECKED);
        assertThat(result.verdict()).isEqualTo(CurrentRoutePassageService.Verdict.PASSED);
        assertThat(result.checkMode()).isEqualTo(CurrentRouteCheckMode.PASS_NEARBY);
        assertThat(result.modeClarification()).isNull();
        assertThat(result.detail()).contains("没有证据表明实际通过");
        verifyNoInteractions(clarificationCache);
    }

    @Test
    void explicitActionStillUsesTheRequestedMode() {
        PassengerRouteSnapshot snapshot = snapshot();
        when(snapshotCache.findForReplay("route-ref", 10001L, "AIC-1"))
                .thenReturn(Optional.of(snapshot));
        when(snapshotCache.find("route-ref", 10001L, "AIC-1", 3L))
                .thenReturn(Optional.of(snapshot));
        AmapPoiCandidate candidate = candidate("五常收费站(G25长深高速入口北向)");
        when(poiTools.searchCurrentRouteCheckCandidates("五常收费站(G25长深高速入口北向)", null))
                .thenReturn(List.of(candidate));
        when(poiTools.currentRouteCheckBusinessType(candidate)).thenReturn(BusinessType.TOLL_STATION);
        when(passageService.check(eq(snapshot), any(), eq(CurrentRouteCheckMode.ENTER_HIGHWAY)))
                .thenReturn(new CurrentRoutePassageService.CheckResult(
                        CurrentRoutePassageService.Verdict.CONFLICT,
                        "TOLL_ACTION_DIRECTION_CONFLICT",
                        "属于驶出高速，不是从该入口驶入"));

        PassengerCurrentRouteCheckService.Result result = service.check(
                10001L, "AIC-1", "route-ref", 4L,
                "五常收费站(G25长深高速入口北向)", null,
                CurrentRouteCheckMode.ENTER_HIGHWAY, "这条路线会从五常入口上高速吗");

        assertThat(result.status()).isEqualTo(PassengerCurrentRouteCheckService.Status.CHECKED);
        assertThat(result.checkMode()).isEqualTo(CurrentRouteCheckMode.ENTER_HIGHWAY);
        assertThat(result.verdict()).isEqualTo(CurrentRoutePassageService.Verdict.CONFLICT);
        assertThat(result.modeClarification()).isNull();
    }

    @Test
    void stationLevelCheckGroupsDirectionVariantsAndIgnoresOtherStations() {
        PassengerRouteSnapshot snapshot = snapshot();
        stubSnapshot(snapshot);
        List<AmapPoiCandidate> candidates = List.of(
                candidate("紫金港收费站(S14杭长宜高速入口东向)", "杭州市"),
                candidate("紫金港收费站(S14杭长宜高速出口安吉方向)", "杭州市"),
                candidate("三墩收费站(留石高架路东向)", "杭州市"),
                candidate("三墩收费站(G25长深高速东入口)", "杭州市"),
                candidate("紫金港收费站(S14杭长宜高速东向)", "杭州市"),
                candidate("三墩收费站(G25长深高速西入口)", "杭州市"),
                candidate("紫金港收费站(S14杭长宜高速西向)", "杭州市"),
                candidate("三墩收费站(留石高架路西向)", "杭州市"),
                candidate("良渚收费站(G25长深高速出口)", "杭州市"),
                candidate("良渚收费站(G25长深高速入口)", "杭州市"));
        when(poiTools.searchCurrentRouteCheckCandidates("三墩高速收费站", null))
                .thenReturn(candidates);
        when(poiTools.currentRouteCheckBusinessType(any())).thenReturn(BusinessType.TOLL_STATION);
        List<String> evaluated = new ArrayList<>();
        when(passageService.checkRelation(eq(snapshot), any())).thenAnswer(invocation -> {
            AmapPoiCandidate raw = invocation.getArgument(1);
            evaluated.add(raw.getName());
            return new RelationCheck(CurrentRoutePassageService.Verdict.PASSED,
                    CurrentRouteCheckMode.PASS_NEARBY, "ROUTE_PASSES_NEARBY_ONLY",
                    "路线折线与目标地点最近约464米，但没有证据表明实际通过该地点");
        });

        PassengerCurrentRouteCheckService.Result result = service.check(
                10001L, "AIC-1", "route-ref", 4L, "三墩高速收费站", null,
                CurrentRouteCheckMode.UNSPECIFIED, "经过三墩高速收费站吗");

        assertThat(result.status()).isEqualTo(PassengerCurrentRouteCheckService.Status.CHECKED);
        assertThat(result.target().name()).isEqualTo("三墩收费站");
        assertThat(result.checkMode()).isEqualTo(CurrentRouteCheckMode.PASS_NEARBY);
        assertThat(result.choices()).isEmpty();
        assertThat(result.modeClarification()).isNull();
        assertThat(evaluated).hasSize(4)
                .allMatch(name -> name.contains("三墩"))
                .noneMatch(name -> name.contains("紫金港") || name.contains("良渚"));
        verifyNoInteractions(placeChoiceCache);
    }

    @Test
    void stationLevelCheckPrefersActualPassageOverNearby() {
        PassengerRouteSnapshot snapshot = snapshot();
        stubSnapshot(snapshot);
        List<AmapPoiCandidate> candidates = List.of(
                candidate("三墩收费站(留石高架路东向)", "杭州市"),
                candidate("三墩收费站(G25长深高速东入口)", "杭州市"),
                candidate("三墩收费站(G25长深高速西入口)", "杭州市"));
        when(poiTools.searchCurrentRouteCheckCandidates("三墩高速收费站", null))
                .thenReturn(candidates);
        when(poiTools.currentRouteCheckBusinessType(any())).thenReturn(BusinessType.TOLL_STATION);
        when(passageService.checkRelation(eq(snapshot), any())).thenAnswer(invocation -> {
            AmapPoiCandidate raw = invocation.getArgument(1);
            if (raw.getName().contains("东入口")) {
                return new RelationCheck(CurrentRoutePassageService.Verdict.PASSED,
                        CurrentRouteCheckMode.VISIT_PLACE, "PLACE_ARRIVAL_MATCHED",
                        "目标收费站附近有对应互通的到达收费站事件");
            }
            return new RelationCheck(CurrentRoutePassageService.Verdict.PASSED,
                    CurrentRouteCheckMode.PASS_NEARBY, "ROUTE_PASSES_NEARBY_ONLY",
                    "路线折线与目标地点最近约464米，但没有证据表明实际通过该地点");
        });

        PassengerCurrentRouteCheckService.Result result = service.check(
                10001L, "AIC-1", "route-ref", 4L, "三墩高速收费站", null,
                CurrentRouteCheckMode.UNSPECIFIED, "经过三墩高速收费站吗");

        assertThat(result.checkMode()).isEqualTo(CurrentRouteCheckMode.VISIT_PLACE);
        assertThat(result.verdict()).isEqualTo(CurrentRoutePassageService.Verdict.PASSED);
        assertThat(result.target().name()).isEqualTo("三墩收费站");
    }

    @Test
    void explicitDirectionDoesNotMergeIntoStationGroup() {
        PassengerRouteSnapshot snapshot = snapshot();
        stubSnapshot(snapshot);
        List<AmapPoiCandidate> candidates = List.of(
                candidate("三墩收费站(G25长深高速东入口)", "杭州市"),
                candidate("三墩收费站(G25长深高速西入口)", "杭州市"));
        when(poiTools.searchCurrentRouteCheckCandidates("三墩东入口", null)).thenReturn(candidates);
        when(poiTools.currentRouteCheckBusinessType(any())).thenReturn(BusinessType.TOLL_STATION);
        when(placeChoiceCache.saveCurrentRouteCheck(anyLong(), any(), anyLong(), anyLong(), any(),
                any(), any(), any(), any())).thenReturn(choiceTask(snapshot));

        PassengerCurrentRouteCheckService.Result result = service.check(
                10001L, "AIC-1", "route-ref", 4L, "三墩东入口", null,
                CurrentRouteCheckMode.UNSPECIFIED, "经过三墩东入口吗");

        assertThat(result.status())
                .isEqualTo(PassengerCurrentRouteCheckService.Status.NEEDS_PLACE_CHOICE);
        assertThat(result.choices()).hasSize(1);
        verify(passageService, never()).checkRelation(
                any(PassengerRouteSnapshot.class), any(AmapPoiCandidate.class));
    }

    @Test
    void stationLevelCheckAsksForCityWhenSameStationNameSpansCities() {
        PassengerRouteSnapshot snapshot = snapshot();
        stubSnapshot(snapshot);
        List<AmapPoiCandidate> candidates = List.of(
                candidate("三墩收费站(G25长深高速东入口)", "杭州市"),
                candidate("三墩收费站(G25长深高速西入口)", "湖州市"));
        when(poiTools.searchCurrentRouteCheckCandidates("三墩高速收费站", null))
                .thenReturn(candidates);
        when(poiTools.currentRouteCheckBusinessType(any())).thenReturn(BusinessType.TOLL_STATION);

        PassengerCurrentRouteCheckService.Result result = service.check(
                10001L, "AIC-1", "route-ref", 4L, "三墩高速收费站", null,
                CurrentRouteCheckMode.UNSPECIFIED, "经过三墩高速收费站吗");

        assertThat(result.status())
                .isEqualTo(PassengerCurrentRouteCheckService.Status.PLACE_UNVERIFIABLE);
        assertThat(result.detail()).contains("多个同名收费站");
        verify(passageService, never()).checkRelation(
                any(PassengerRouteSnapshot.class), any(AmapPoiCandidate.class));
    }

    private static ChoiceTask choiceTask(PassengerRouteSnapshot snapshot) {
        Instant now = Instant.now();
        Place choicePlace = new Place("B0TEST001", "三墩收费站(G25长深高速东入口)",
                "西湖区", "杭州市", new GeoPoint(120.046785, 30.279236));
        PlaceCandidate candidate = new PlaceCandidate(choicePlace, BusinessType.TOLL_STATION,
                List.of(), "收费站");
        Choice choice = new Choice("PC-00000000-0000-4000-8000-000000000001", candidate);
        return new ChoiceTask("AIT-00000000-0000-4000-8000-000000000001",
                10001L, "AIC-1", 3L, 4L, ChoiceRole.WAYPOINT,
                PassengerRouteIntent.CURRENT_ROUTE_CHECK, "经过三墩东入口吗",
                snapshot.origin(), snapshot.destination(), null, null,
                List.of(choice), now, now.plusSeconds(300));
    }

    private void stubSnapshot(PassengerRouteSnapshot snapshot) {
        when(snapshotCache.findForReplay("route-ref", 10001L, "AIC-1"))
                .thenReturn(Optional.of(snapshot));
        when(snapshotCache.find("route-ref", 10001L, "AIC-1", 3L))
                .thenReturn(Optional.of(snapshot));
    }

    private static AmapPoiCandidate candidate(String name) {
        return candidate(name, "杭州");
    }

    private static AmapPoiCandidate candidate(String name, String city) {
        AmapPoiCandidate candidate = new AmapPoiCandidate();
        candidate.setPoiId("B0TEST001");
        candidate.setName(name);
        candidate.setType("交通设施服务;收费站");
        candidate.setTypeCode("180200");
        candidate.setCity(city);
        candidate.setAddress("余杭区");
        Point center = new Point();
        center.setLng(120.046785);
        center.setLat(30.279236);
        candidate.setCenterPoint(center);
        return candidate;
    }

    private static PassengerRouteSnapshot snapshot() {
        Instant generatedAt = Instant.now().minusSeconds(30);
        return new PassengerRouteSnapshot(
                "route-ref", 10001L, "AIC-1", 3L,
                new Place("O-1", "德清收费站", "德清县", "湖州", new GeoPoint(119.9, 30.5)),
                new Place("D-1", "西溪湿地", "西湖区", "杭州", new GeoPoint(120.0, 30.3)),
                "gaode", PassengerRouteSnapshot.COORDINATE_SYSTEM_GCJ02,
                PassengerRouteSnapshot.TRAVEL_MODE_DRIVING,
                PassengerRouteSnapshot.RouteKind.DEFAULT,
                List.of(new GeoPoint(119.9, 30.5), new GeoPoint(120.0, 30.3)),
                List.of(new PassengerRouteSnapshot.NavigationStep(
                        "向前行驶", "直行", "", "东", "测试道路", List.of())),
                40_000L, 3_000L, generatedAt, generatedAt.plusSeconds(300),
                null, List.of(), null);
    }
}
