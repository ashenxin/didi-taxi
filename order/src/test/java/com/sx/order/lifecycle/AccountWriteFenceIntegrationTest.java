package com.sx.order.lifecycle;

import com.sx.order.dao.OrderEventEntityMapper;
import com.sx.order.dao.OrderIdempotentRecordMapper;
import com.sx.order.dao.OrderOutboxEventMapper;
import com.sx.order.dao.TripOrderEntityMapper;
import com.sx.order.lifecycle.dao.OrderAccountLifecycleProjectionMapper;
import com.sx.order.lifecycle.dao.OrderAccountLifecycleEventInboxMapper;
import com.sx.order.lifecycle.exception.AccountLifecycleBlockedException;
import com.sx.order.lifecycle.exception.AccountLifecycleUnknownException;
import com.sx.order.lifecycle.model.ApplyOrderLifecycleProjectionCommand;
import com.sx.order.lifecycle.model.OrderLifecycleStatus;
import com.sx.order.lifecycle.service.OrderLifecycleProjectionService;
import com.sx.order.model.dto.CreateOrderBody;
import com.sx.order.model.dto.CreateOrderPreflightRequest;
import com.sx.order.model.dto.Place;
import com.sx.order.service.TripOrderWriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AccountWriteFenceIntegrationTest {

    @Autowired private TripOrderWriteService orders;
    @Autowired private OrderLifecycleProjectionService projections;
    @Autowired private OrderAccountLifecycleProjectionMapper projectionMapper;
    @Autowired private OrderAccountLifecycleEventInboxMapper eventInboxMapper;
    @Autowired private TripOrderEntityMapper orderMapper;
    @Autowired private OrderEventEntityMapper eventMapper;
    @Autowired private OrderOutboxEventMapper outboxMapper;
    @Autowired private OrderIdempotentRecordMapper idempotentMapper;

    @BeforeEach
    void clean() {
        outboxMapper.delete(null);
        eventMapper.delete(null);
        orderMapper.delete(null);
        idempotentMapper.delete(null);
        projectionMapper.delete(null);
        eventInboxMapper.delete(null);
    }

    @Test
    void missingProjectionFailsClosedWithoutCreatingAnySideEffect() {
        assertThatThrownBy(() -> orders.create(body(11001L), "missing-projection"))
                .isInstanceOf(AccountLifecycleUnknownException.class);
        assertNoCreateSideEffects();
    }

    @Test
    void cancellingProjectionBlocksCreateWithoutCreatingAnySideEffect() {
        seedActive(11002L);
        projections.apply(command(11002L, 0, OrderLifecycleStatus.CANCELLING, 1,
                "op-11002", "event-11002-1"));

        assertThatThrownBy(() -> orders.create(body(11002L), "cancelling"))
                .isInstanceOf(AccountLifecycleBlockedException.class);
        assertNoCreateSideEffects();
    }

    @Test
    void missingAndCancellingProjectionAlsoBlockCreatePreflight() {
        assertThatThrownBy(() -> orders.preflightCreate(preflight(body(11004L)), "missing-preflight"))
                .isInstanceOf(AccountLifecycleUnknownException.class);

        seedActive(11005L);
        projections.apply(command(11005L, 0, OrderLifecycleStatus.CANCELLING, 1,
                "op-11005", "event-11005-1"));
        assertThatThrownBy(() -> orders.preflightCreate(preflight(body(11005L)), "blocked-preflight"))
                .isInstanceOf(AccountLifecycleBlockedException.class);
        assertNoCreateSideEffects();
    }

    @Test
    void activeProjectionAllowsCreateButSuccessfulReplaySurvivesLaterCancellation() {
        seedActive(11003L);
        String orderNo = orders.create(body(11003L), "replay-after-cancelling");
        projections.apply(command(11003L, 0, OrderLifecycleStatus.CANCELLING, 1,
                "op-11003", "event-11003-1"));

        assertThat(orders.create(body(11003L), "replay-after-cancelling")).isEqualTo(orderNo);
        assertThat(orderMapper.selectCount(null)).isEqualTo(1);
        assertThat(eventMapper.selectCount(null)).isEqualTo(1);
        assertThat(outboxMapper.selectCount(null)).isEqualTo(1);
    }

    @Test
    void cancelledDisabledAndCorruptProjectionStatesAllFailClosed() {
        seedActive(11006L);
        projections.apply(command(11006L, 1, OrderLifecycleStatus.CANCELLED, 1,
                "op-11006", "event-11006-1"));
        assertThatThrownBy(() -> orders.create(body(11006L), "cancelled"))
                .isInstanceOf(AccountLifecycleBlockedException.class);

        seedActive(11007L);
        var disabled = projectionMapper.selectById(11007L).setBusinessStatus(1);
        projectionMapper.updateById(disabled);
        assertThatThrownBy(() -> orders.create(body(11007L), "disabled"))
                .isInstanceOf(AccountLifecycleBlockedException.class);

        seedActive(11008L);
        var corrupt = projectionMapper.selectById(11008L).setLifecycleStatus("MYSTERY");
        projectionMapper.updateById(corrupt);
        assertThatThrownBy(() -> orders.create(body(11008L), "corrupt"))
                .isInstanceOf(AccountLifecycleUnknownException.class);
        assertNoCreateSideEffects();
    }

    @Test
    void successfulPreflightReplayAlsoSurvivesLaterCancellation() {
        seedActive(11009L);
        CreateOrderBody body = body(11009L);
        String orderNo = orders.create(body, "preflight-replay-after-cancelling");
        projections.apply(command(11009L, 0, OrderLifecycleStatus.CANCELLING, 1,
                "op-11009", "event-11009-1"));

        var result = orders.preflightCreate(preflight(body), "preflight-replay-after-cancelling");
        assertThat(result.decision()).isEqualTo("REPLAY_SUCCESS");
        assertThat(result.orderNo()).isEqualTo(orderNo);
    }

    private void assertNoCreateSideEffects() {
        assertThat(orderMapper.selectCount(null)).isZero();
        assertThat(eventMapper.selectCount(null)).isZero();
        assertThat(outboxMapper.selectCount(null)).isZero();
        assertThat(idempotentMapper.selectCount(null)).isZero();
    }

    private void seedActive(long customerId) {
        projections.seedActive(customerId, "seed-" + customerId, LocalDateTime.now());
    }

    private static ApplyOrderLifecycleProjectionCommand command(
            long customerId, int businessStatus, OrderLifecycleStatus status, long version,
            String operationNo, String eventId) {
        return new ApplyOrderLifecycleProjectionCommand(customerId, businessStatus,
                status.name(), version, operationNo, eventId, LocalDateTime.now());
    }

    private static CreateOrderBody body(Long passengerId) {
        CreateOrderBody body = new CreateOrderBody();
        body.setPassengerId(passengerId);
        body.setProvinceCode("330000");
        body.setCityCode("330100");
        body.setProductCode("ECONOMY");
        body.setOrigin(place("起点", "30.1", "120.1"));
        body.setDest(place("终点", "30.2", "120.2"));
        body.setEstimatedAmount(new BigDecimal("30.00"));
        body.setFareRuleId(1L);
        body.setFareRuleSnapshot("{\"baseFare\":12.00}");
        body.setFareCalculationVersion("fare-v1");
        body.setPlannedDistanceMeters(10000L);
        body.setPlannedDurationSeconds(1200L);
        body.setDistanceSource("LOCAL_MOCK_ROUTE");
        body.setRouteMockVersion("mock-route-v1");
        return body;
    }

    private static CreateOrderPreflightRequest preflight(CreateOrderBody body) {
        return new CreateOrderPreflightRequest(body.getPassengerId(), body.getProvinceCode(),
                body.getCityCode(), body.getProductCode(), body.getOrigin(), body.getDest());
    }

    private static Place place(String address, String lat, String lng) {
        Place place = new Place();
        place.setAddress(address);
        place.setLat(new BigDecimal(lat));
        place.setLng(new BigDecimal(lng));
        return place;
    }
}
