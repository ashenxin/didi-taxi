package com.sx.map.ai.service;

import com.sx.map.ai.dto.RouteCardDto;
import com.sx.map.ai.model.PassengerRouteSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouteCardAssemblerTest {

    private static final PassengerRouteSnapshot.GeoPoint START =
            new PassengerRouteSnapshot.GeoPoint(120.1, 30.2);
    private static final PassengerRouteSnapshot.GeoPoint END =
            new PassengerRouteSnapshot.GeoPoint(120.2, 30.3);

    private final RouteCardAssembler assembler = new RouteCardAssembler();

    @Test
    void mapsSnapshotToDisplayCardWithoutVia() {
        Instant generatedAt = Instant.parse("2026-09-18T02:00:00Z");
        PassengerRouteSnapshot snapshot = snapshot(generatedAt);

        RouteCardDto card = assembler.fromSnapshot(snapshot);

        assertThat(card.routeRef()).isEqualTo(snapshot.routeRef());
        assertThat(card.origin().name()).isEqualTo("德清高速路口");
        assertThat(card.destination().name()).isEqualTo("西溪湿地");
        assertThat(card.via()).isNull();
        assertThat(card.route().coordinateSystem()).isEqualTo("GCJ02");
        assertThat(card.route().distanceMeters()).isEqualTo(45600);
        assertThat(card.route().durationSeconds()).isEqualTo(3300);
        assertThat(card.route().polyline()).containsExactly(
                new RouteCardDto.GeoPointView(120.1, 30.2),
                new RouteCardDto.GeoPointView(120.2, 30.3));
        assertThat(card.generatedAt()).isEqualTo(generatedAt);
        assertThat(card.expiresAt()).isEqualTo(generatedAt.plusSeconds(300));
    }

    @Test
    void mapsVerifiedViaPlaceToDisplayCard() {
        Instant generatedAt = Instant.parse("2026-09-18T02:00:00Z");
        PassengerRouteSnapshot base = snapshot(generatedAt);
        PassengerRouteSnapshot.Place viaPlace = new PassengerRouteSnapshot.Place(
                "P-1", "五常收费站", "余杭塘路", "杭州", START);
        PassengerRouteSnapshot snapshot = new PassengerRouteSnapshot(
                base.routeRef(), base.customerId(), base.conversationNo(), base.conditionVersion(),
                base.origin(), base.destination(), base.provider(), base.coordinateSystem(),
                base.travelMode(), PassengerRouteSnapshot.RouteKind.VIA_PLACE,
                base.polyline(), base.navigationSteps(), base.distanceMeters(), base.durationSeconds(),
                base.generatedAt(), base.expiresAt(),
                new PassengerRouteSnapshot.ViaPlace(viaPlace,
                        PassengerRouteSnapshot.BusinessType.TOLL_STATION, List.of()),
                List.of(START), new PassengerRouteSnapshot.PassageVerification(
                        "PASSED", "TOLL_ARRIVAL_MATCHED"));

        RouteCardDto card = assembler.fromSnapshot(snapshot);

        assertThat(card.via()).isEqualTo(new RouteCardDto.PlaceView("五常收费站"));
    }

    private static PassengerRouteSnapshot snapshot(Instant generatedAt) {
        return new PassengerRouteSnapshot(
                "9f8b2a44-6c1d-4b5e-9a0f-2c3d4e5f6a7b", 42L, "conversation-test", 1L,
                new PassengerRouteSnapshot.Place(null, "德清高速路口", "德清县", "湖州", START),
                new PassengerRouteSnapshot.Place(null, "西溪湿地", "西湖区", "杭州", END),
                "gaode", "GCJ02", "DRIVING", PassengerRouteSnapshot.RouteKind.DEFAULT,
                List.of(START, END),
                List.of(new PassengerRouteSnapshot.NavigationStep(
                        "进入道路", "直行", "", "东", "测试路", List.of(START, END))),
                45600, 3300, generatedAt, generatedAt.plusSeconds(300),
                null, List.of(), null);
    }
}
