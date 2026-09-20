package com.sx.map.ai.service;

import com.sx.map.ai.model.PassengerRouteIntent;
import com.sx.map.ai.model.PassengerRouteSnapshot;
import com.sx.map.ai.service.PassengerRouteEndpointPreparationService.PreparationResult;
import com.sx.map.ai.service.PassengerRouteEndpointPreparationService.PreparationStatus;
import com.sx.map.ai.service.PassengerRouteEndpointResolver.CandidateSummary;
import com.sx.map.ai.service.PassengerRouteEndpointResolver.Resolution;
import com.sx.map.ai.service.PassengerRouteEndpointResolver.SelectableCandidate;
import com.sx.map.ai.service.PassengerRouteEndpointResolver.Status;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.Choice;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.ChoiceRole;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.ChoiceTask;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.PlaceCandidate;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.SelectedChoice;
import com.sx.map.ai.service.PassengerRouteConditionsCache.ConfirmedConditions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PassengerRouteEndpointPreparationServiceTest {
    private static final String ORIGINAL_TEXT = "规划德清到杭州东站的路线";

    private final PassengerRouteEndpointResolver resolver = mock(PassengerRouteEndpointResolver.class);
    private final PassengerRouteConditionsCache conditionsCache =
            mock(PassengerRouteConditionsCache.class);
    private final PassengerRoutePlaceChoiceCache choiceCache = mock(PassengerRoutePlaceChoiceCache.class);
    private final PassengerRouteEndpointPreparationService service =
            new PassengerRouteEndpointPreparationService(resolver, conditionsCache, choiceCache);

    @Test
    void ambiguousOriginIsSavedAsOwnedChoicesWithOnlyDisplaySummariesReturned() {
        PassengerRouteSnapshot.Place first = place("P-1", "德清", "湖州", "德清县", 120.0);
        PassengerRouteSnapshot.Place second = place("P-2", "德清", "杭州", "同名地址", 120.1);
        PassengerRouteSnapshot.Place destination = place("P-3", "杭州东站", "杭州", "天城路", 120.2);
        when(resolver.resolve("德清", null)).thenReturn(ambiguous(first, second));
        when(resolver.resolve("杭州东站", null)).thenReturn(unique(destination));
        ChoiceTask task = choiceTask(ChoiceRole.ORIGIN, null, destination, first, second);
        when(choiceCache.save(eq(42L), eq("AIC-1"), eq(7L), eq(7L),
                eq(ChoiceRole.ORIGIN), eq(PassengerRouteIntent.DEFAULT_ROUTE), eq(ORIGINAL_TEXT),
                isNull(), eq(destination), anyList())).thenReturn(task);

        PreparationResult result = prepare();

        assertThat(result.status()).isEqualTo(PreparationStatus.NEEDS_PLACE_CHOICE);
        assertThat(result.choiceRole()).isEqualTo(ChoiceRole.ORIGIN);
        assertThat(result.choiceTaskNo()).isEqualTo(task.taskNo());
        assertThat(result.choices()).hasSize(2);
        assertThat(result.expiresAt()).isEqualTo(task.expiresAt());
        verify(choiceCache).save(eq(42L), eq("AIC-1"), eq(7L), eq(7L),
                eq(ChoiceRole.ORIGIN), eq(PassengerRouteIntent.DEFAULT_ROUTE), eq(ORIGINAL_TEXT),
                isNull(), eq(destination),
                org.mockito.ArgumentMatchers.argThat(candidates ->
                        candidates.size() == 2
                                && "P-1".equals(candidates.get(0).place().placeId())
                                && "P-2".equals(candidates.get(1).place().placeId())
                                && candidates.stream().allMatch(candidate -> candidate.businessType() == null)));
        verify(conditionsCache, never()).saveConfirmed(
                eq(42L), eq("AIC-1"), eq(7L),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ambiguousDestinationKeepsResolvedOriginAndSavesDestinationChoices() {
        PassengerRouteSnapshot.Place origin = place("P-1", "德清", "湖州", "德清县", 120.0);
        PassengerRouteSnapshot.Place destination = place("P-2", "杭州东站", "杭州", "天城路", 120.2);
        when(resolver.resolve("德清", null)).thenReturn(unique(origin));
        when(resolver.resolve("杭州东站", null)).thenReturn(ambiguous(destination));
        ChoiceTask task = choiceTask(ChoiceRole.DESTINATION, origin, null, destination);
        when(choiceCache.save(eq(42L), eq("AIC-1"), eq(7L), eq(7L),
                eq(ChoiceRole.DESTINATION), eq(PassengerRouteIntent.DEFAULT_ROUTE), eq(ORIGINAL_TEXT),
                eq(origin), isNull(), anyList())).thenReturn(task);

        PreparationResult result = prepare();

        assertThat(result.status()).isEqualTo(PreparationStatus.NEEDS_PLACE_CHOICE);
        assertThat(result.choiceRole()).isEqualTo(ChoiceRole.DESTINATION);
        assertThat(result.choices()).singleElement()
                .satisfies(choice -> assertThat(choice.name()).isEqualTo("杭州东站"));
        verify(choiceCache).save(eq(42L), eq("AIC-1"), eq(7L), eq(7L),
                eq(ChoiceRole.DESTINATION), eq(PassengerRouteIntent.DEFAULT_ROUTE), eq(ORIGINAL_TEXT),
                eq(origin), isNull(), anyList());
    }

    @Test
    void bothAmbiguousEndpointsOfferOriginFirstWithoutGuessingDestination() {
        PassengerRouteSnapshot.Place origin = place("P-1", "德清", "湖州", "德清县", 120.0);
        PassengerRouteSnapshot.Place destination = place("P-2", "杭州东站", "杭州", "天城路", 120.2);
        when(resolver.resolve("德清", null)).thenReturn(ambiguous(origin));
        when(resolver.resolve("杭州东站", null)).thenReturn(ambiguous(destination));
        when(choiceCache.save(eq(42L), eq("AIC-1"), eq(7L), eq(7L),
                eq(ChoiceRole.ORIGIN), eq(PassengerRouteIntent.DEFAULT_ROUTE), eq(ORIGINAL_TEXT),
                isNull(), isNull(), eq("杭州东站"), isNull(), anyList()))
                .thenReturn(choiceTask(ChoiceRole.ORIGIN, null, null, origin));

        PreparationResult result = prepare();

        assertThat(result.status()).isEqualTo(PreparationStatus.NEEDS_PLACE_CHOICE);
        assertThat(result.choiceRole()).isEqualTo(ChoiceRole.ORIGIN);
        verify(choiceCache).save(eq(42L), eq("AIC-1"), eq(7L), eq(7L),
                eq(ChoiceRole.ORIGIN), eq(PassengerRouteIntent.DEFAULT_ROUTE), eq(ORIGINAL_TEXT),
                isNull(), isNull(), eq("杭州东站"), isNull(), anyList());
    }

    @Test
    void missingTrustedOriginCandidatesStaysInTextClarification() {
        PassengerRouteSnapshot.Place destination = place("P-2", "杭州东站", "杭州", "天城路", 120.2);
        when(resolver.resolve("德清", null)).thenReturn(new Resolution(
                Status.INVALID_MAP_DATA, null, List.of(summary("德清", "湖州", "无效地点")), List.of()));
        when(resolver.resolve("杭州东站", null)).thenReturn(unique(destination));

        PreparationResult result = prepare();

        assertThat(result.status()).isEqualTo(PreparationStatus.NEEDS_CLARIFICATION);
        assertThat(result.choices()).isEmpty();
        assertThat(result.expiresAt()).isNull();
        org.mockito.Mockito.verifyNoInteractions(choiceCache, conditionsCache);
    }

    @Test
    void uniqueEndpointsAreConfirmedAndReadyForRouteWithoutSecondConfirmation() {
        PassengerRouteSnapshot.Place origin = place("P-1", "德清", "湖州", "德清县", 120.0);
        PassengerRouteSnapshot.Place destination = place("P-2", "杭州东站", "杭州", "天城路", 120.2);
        when(resolver.resolve("德清", null)).thenReturn(unique(origin));
        when(resolver.resolve("杭州东站", null)).thenReturn(unique(destination));
        Instant createdAt = Instant.parse("2026-09-18T12:55:00Z");
        Instant expiresAt = Instant.parse("2026-09-18T13:00:00Z");
        ConfirmedConditions confirmed = new ConfirmedConditions(42L, "AIC-1", 7L,
                origin, destination, createdAt, expiresAt);
        when(conditionsCache.saveConfirmed(42L, "AIC-1", 7L,
                origin, destination)).thenReturn(confirmed);

        PreparationResult result = prepare();

        assertThat(result.status()).isEqualTo(PreparationStatus.READY_FOR_ROUTE);
        assertThat(result.expiresAt()).isEqualTo(expiresAt);
        assertThat(result.choices()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(choiceCache);
    }

    @Test
    void selectingOriginContinuesToDestinationChoicesThenReadiesTheRoute() {
        PassengerRouteSnapshot.Place origin = place("P-1", "德清", "湖州", "德清县", 120.0);
        PassengerRouteSnapshot.Place destination = place("P-2", "杭州东站", "杭州", "天城路", 120.2);
        PlaceCandidate originCandidate = new PlaceCandidate(origin, null, List.of(), "交通设施");
        PlaceCandidate destinationCandidate = new PlaceCandidate(destination, null, List.of(), "交通设施");
        when(choiceCache.select("AIT-origin", "PC-origin", 42L, "AIC-1", 7L, 7L))
                .thenReturn(Optional.of(new SelectedChoice("AIT-origin", ChoiceRole.ORIGIN,
                        PassengerRouteIntent.DEFAULT_ROUTE, ORIGINAL_TEXT, null, null,
                        "杭州东站", null, originCandidate, Instant.now().plusSeconds(300))));
        when(resolver.resolve("杭州东站", null)).thenReturn(ambiguous(destination));
        ChoiceTask destinationTask = choiceTask(ChoiceRole.DESTINATION, origin, null, destination);
        when(choiceCache.save(eq(42L), eq("AIC-1"), eq(7L), eq(8L),
                eq(ChoiceRole.DESTINATION), eq(PassengerRouteIntent.DEFAULT_ROUTE), eq(ORIGINAL_TEXT),
                eq(origin), isNull(), anyList())).thenReturn(destinationTask);

        PreparationResult next = service.select(42L, "AIC-1", "AIT-origin", "PC-origin", 7L, 7L, 8L);

        assertThat(next.status()).isEqualTo(PreparationStatus.NEEDS_PLACE_CHOICE);
        assertThat(next.choiceRole()).isEqualTo(ChoiceRole.DESTINATION);
        verify(conditionsCache, never()).saveConfirmed(eq(42L), eq("AIC-1"), eq(7L),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        when(choiceCache.select(destinationTask.taskNo(), "PC-destination", 42L, "AIC-1", 7L, 8L))
                .thenReturn(Optional.of(new SelectedChoice(destinationTask.taskNo(), ChoiceRole.DESTINATION,
                        PassengerRouteIntent.DEFAULT_ROUTE, ORIGINAL_TEXT, origin, null,
                        null, null, destinationCandidate, Instant.now().plusSeconds(300))));
        Instant pendingCreatedAt = Instant.now();
        ConfirmedConditions confirmed = new ConfirmedConditions(42L, "AIC-1", 7L,
                origin, destination, pendingCreatedAt, pendingCreatedAt.plusSeconds(120));
        when(conditionsCache.saveConfirmed(42L, "AIC-1", 7L,
                origin, destination)).thenReturn(confirmed);

        PreparationResult ready = service.select(42L, "AIC-1", destinationTask.taskNo(),
                "PC-destination", 7L, 8L, 9L);

        assertThat(ready.status()).isEqualTo(PreparationStatus.READY_FOR_ROUTE);
        assertThat(ready.origin().candidates().getFirst().name()).isEqualTo("德清");
        assertThat(ready.destination().candidates().getFirst().name()).isEqualTo("杭州东站");
    }

    @Test
    void expiredOrForeignChoiceCannotAdvanceRoute() {
        when(choiceCache.select("AIT-old", "PC-old", 42L, "AIC-1", 7L, 7L))
                .thenReturn(Optional.empty());

        PreparationResult result = service.select(42L, "AIC-1", "AIT-old", "PC-old",
                7L, 7L, 8L);

        assertThat(result).isNull();
        org.mockito.Mockito.verifyNoInteractions(conditionsCache, resolver);
    }

    private PreparationResult prepare() {
        return service.prepare(42L, "AIC-1", "AIR-7", 7L,
                "德清", null, "杭州东站", null,
                PassengerRouteIntent.DEFAULT_ROUTE, ORIGINAL_TEXT);
    }

    private static Resolution unique(PassengerRouteSnapshot.Place place) {
        return new Resolution(Status.UNIQUE, place, List.of(), List.of());
    }

    private static Resolution ambiguous(PassengerRouteSnapshot.Place... places) {
        List<SelectableCandidate> selectable = java.util.Arrays.stream(places)
                .map(place -> new SelectableCandidate(
                        summary(place.name(), place.city(), place.address()), place))
                .toList();
        return new Resolution(Status.NEEDS_CLARIFICATION, null,
                selectable.stream().map(SelectableCandidate::summary).toList(), selectable);
    }

    private static CandidateSummary summary(String name, String city, String address) {
        return new CandidateSummary(name, "交通设施", city, null, address);
    }

    private static PassengerRouteSnapshot.Place place(String id, String name, String city,
                                                      String address, double longitude) {
        return new PassengerRouteSnapshot.Place(id, name, address, city,
                new PassengerRouteSnapshot.GeoPoint(longitude, 30.2));
    }

    private static ChoiceTask choiceTask(ChoiceRole role, PassengerRouteSnapshot.Place origin,
                                         PassengerRouteSnapshot.Place destination,
                                         PassengerRouteSnapshot.Place... places) {
        List<Choice> choices = java.util.Arrays.stream(places)
                .map(place -> new Choice("PC-" + UUID.randomUUID(),
                        new PlaceCandidate(place, null, List.of(), "交通设施")))
                .toList();
        return new ChoiceTask("AIT-" + UUID.randomUUID(), 42L, "AIC-1", 7L, 7L,
                role, PassengerRouteIntent.DEFAULT_ROUTE, ORIGINAL_TEXT,
                origin, destination, null, null, choices,
                Instant.parse("2026-09-18T12:55:00Z"),
                Instant.parse("2026-09-18T13:00:00Z"));
    }
}
