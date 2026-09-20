package com.sx.map.ai.service;

import com.sx.map.ai.service.PassengerRouteEndpointResolver.Resolution;
import com.sx.map.ai.service.PassengerRouteEndpointResolver.Status;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.service.AmapPoiSearchService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
