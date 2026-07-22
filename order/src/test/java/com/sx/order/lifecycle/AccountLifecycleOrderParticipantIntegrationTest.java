package com.sx.order.lifecycle;

import com.sx.order.dao.OrderEventEntityMapper;
import com.sx.order.dao.OrderIdempotentRecordMapper;
import com.sx.order.dao.OrderOutboxEventMapper;
import com.sx.order.dao.TripOrderEntityMapper;
import com.sx.order.dao.TripOrderSettlementMapper;
import com.sx.order.lifecycle.dao.OrderAccountLifecycleProjectionMapper;
import com.sx.order.lifecycle.dao.OrderAccountLifecycleEventInboxMapper;
import com.sx.order.lifecycle.dao.OrderLifecycleParticipantInboxMapper;
import com.sx.order.lifecycle.exception.OrderLifecycleCommandConflictException;
import com.sx.order.lifecycle.model.OrderLifecycleBlocker;
import com.sx.order.lifecycle.model.OrderLifecycleCommand;
import com.sx.order.lifecycle.model.OrderLifecycleDecision;
import com.sx.order.lifecycle.model.OrderLifecyclePrecheckRequest;
import com.sx.order.lifecycle.model.OrderLifecycleParticipantInbox;
import com.sx.order.lifecycle.service.AccountLifecycleOrderParticipantService;
import com.sx.order.lifecycle.service.OrderLifecycleProjectionService;
import com.sx.order.model.TripOrder;
import com.sx.order.model.TripOrderSettlement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AccountLifecycleOrderParticipantIntegrationTest {

    @Autowired private AccountLifecycleOrderParticipantService participant;
    @Autowired private OrderLifecycleProjectionService projections;
    @SpyBean private OrderLifecycleParticipantInboxMapper inboxMapper;
    @Autowired private OrderAccountLifecycleProjectionMapper projectionMapper;
    @Autowired private OrderAccountLifecycleEventInboxMapper eventInboxMapper;
    @Autowired private TripOrderEntityMapper orderMapper;
    @Autowired private TripOrderSettlementMapper settlementMapper;
    @Autowired private OrderEventEntityMapper eventMapper;
    @Autowired private OrderOutboxEventMapper outboxMapper;
    @Autowired private OrderIdempotentRecordMapper idempotentMapper;
    @Autowired private MockMvc mockMvc;

    @BeforeEach
    void clean() {
        reset(inboxMapper);
        settlementMapper.delete(null);
        outboxMapper.delete(null);
        eventMapper.delete(null);
        orderMapper.delete(null);
        idempotentMapper.delete(null);
        inboxMapper.delete(null);
        projectionMapper.delete(null);
        eventInboxMapper.delete(null);
    }

    @Test
    void inboxFailureRollsBackProjectionTransition() {
        seedActive(12008L);
        doThrow(new IllegalStateException("injected inbox failure"))
                .when(inboxMapper).updateById(any(OrderLifecycleParticipantInbox.class));

        assertThatThrownBy(() -> participant.fence(
                command("op-rollback", 12008L, 1L, "event-rollback")))
                .isInstanceOf(IllegalStateException.class);

        reset(inboxMapper);
        assertThat(projectionMapper.selectById(12008L).getLifecycleStatus()).isEqualTo("ACTIVE");
        assertThat(inboxMapper.selectCount(null)).isZero();
    }

    @Test
    void sameOperationAndHashReplaysButDifferentPayloadConflicts() {
        seedActive(12001L);
        OrderLifecycleCommand command = command("op-replay", 12001L, 1L, "event-replay");

        var first = participant.fence(command);
        var replay = participant.fence(command);

        assertThat(first.decision()).isEqualTo(OrderLifecycleDecision.PASS);
        assertThat(replay).isEqualTo(first);
        assertThat(inboxMapper.selectCount(null)).isEqualTo(1);
        assertThatThrownBy(() -> participant.fence(
                command("op-replay", 12002L, 1L, "event-different")))
                .isInstanceOf(OrderLifecycleCommandConflictException.class);
        assertThat(inboxMapper.selectCount(null)).isEqualTo(1);
    }

    @Test
    void fenceAdvancesProjectionEvenWhenActiveOrderBlocksCancellation() {
        seedActive(12003L);
        orderMapper.insert(order(12003L, "ACTIVE-12003", 2));

        var result = participant.fence(command("op-active", 12003L, 3L, "event-active"));

        assertThat(result.decision()).isEqualTo(OrderLifecycleDecision.BLOCKED);
        assertThat(result.blockers()).containsExactly(new OrderLifecycleBlocker(
                "ACTIVE_ORDER", "ORDER", "ACTIVE-12003", "CANCEL_ORDER"));
        assertThat(projectionMapper.selectById(12003L).getLifecycleStatus()).isEqualTo("CANCELLING");
        assertThat(projectionMapper.selectById(12003L).getLifecycleVersion()).isEqualTo(3L);
    }

    @Test
    void unpaidAndUnknownSettlementMapToStableBlockers() {
        seedActive(12004L);
        TripOrder unpaid = order(12004L, "UNPAID-12004", 5);
        orderMapper.insert(unpaid);
        settlementMapper.insert(settlement(unpaid, "PAYMENT_REQUIRED", 0));
        assertThat(participant.fence(command("op-unpaid", 12004L, 1L, "event-unpaid")).blockers())
                .containsExactly(new OrderLifecycleBlocker(
                        "UNPAID_ORDER", "ORDER", "UNPAID-12004", "PAY_OUTSTANDING"));

        clean();
        seedActive(12005L);
        orderMapper.insert(order(12005L, "UNKNOWN-12005", 5));
        assertThat(participant.fence(command("op-unknown", 12005L, 1L, "event-unknown")).blockers())
                .containsExactly(new OrderLifecycleBlocker(
                        "SETTLEMENT_UNKNOWN", "ORDER", "UNKNOWN-12005", "CONTACT_OPERATIONS"));
    }

    @Test
    void precheckIsReadOnlyAndUnknownStepIsRejectedBeforeStateChanges() {
        seedActive(12006L);
        orderMapper.insert(order(12006L, "PRECHECK-12006", 2));

        var result = participant.precheck(new OrderLifecyclePrecheckRequest(12006L));

        assertThat(result.decision()).isEqualTo(OrderLifecycleDecision.BLOCKED);
        assertThat(inboxMapper.selectCount(null)).isZero();
        assertThat(projectionMapper.selectById(12006L).getLifecycleStatus()).isEqualTo("ACTIVE");

        assertThatThrownBy(() -> participant.fence(new OrderLifecycleCommand(
                "op-invalid", "UNKNOWN_STEP", 12006L, "CANCELLING", 1L,
                "event-invalid", LocalDateTime.now())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(inboxMapper.selectCount(null)).isZero();
        assertThat(projectionMapper.selectById(12006L).getLifecycleStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void internalHttpContractRequiresIndependentTokenAndExposesStableResultQuery() throws Exception {
        seedActive(12007L);
        String command = """
                {"operationNo":"op-http","stepCode":"ORDER_FINAL_CHECK","customerId":12007,
                 "targetLifecycleStatus":"CANCELLING","lifecycleVersion":1,
                 "sourceEventId":"event-http","requestedAt":"2026-07-22T14:00:00"}
                """;

        mockMvc.perform(post("/api/v1/internal/account-lifecycle/order/fence")
                        .contentType(MediaType.APPLICATION_JSON).content(command))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/internal/account-lifecycle/order/fence")
                        .header("X-Internal-Token", "dev-order-lifecycle-change-me")
                        .contentType(MediaType.APPLICATION_JSON).content(command))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.decision").value("PASS"));
        mockMvc.perform(get("/api/v1/internal/account-lifecycle/order/results/op-http/ORDER_FINAL_CHECK")
                        .header("X-Internal-Token", "dev-order-lifecycle-change-me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("PASS"));
    }

    private void seedActive(long customerId) {
        projections.seedActive(customerId, "seed-participant-" + customerId, LocalDateTime.now());
    }

    private static OrderLifecycleCommand command(String operationNo, long customerId,
                                                  long version, String eventId) {
        return new OrderLifecycleCommand(operationNo, "ORDER_FINAL_CHECK", customerId,
                "CANCELLING", version, eventId, LocalDateTime.now());
    }

    private static TripOrder order(long customerId, String orderNo, int status) {
        LocalDateTime now = LocalDateTime.now();
        return new TripOrder().setOrderNo(orderNo).setPassengerId(customerId)
                .setProductCode("ECONOMY").setProvinceCode("330000").setCityCode("330100")
                .setOriginAddress("起点").setOriginLat(new BigDecimal("30.1"))
                .setOriginLng(new BigDecimal("120.1"))
                .setDestAddress("终点").setDestLat(new BigDecimal("30.2"))
                .setDestLng(new BigDecimal("120.2"))
                .setStatus(status).setBlocksNewOrder(1).setOfferRound(0)
                .setCreatedAt(now).setUpdatedAt(now).setIsDeleted(0);
    }

    private static TripOrderSettlement settlement(TripOrder order, String status, int manual) {
        LocalDateTime now = LocalDateTime.now();
        return new TripOrderSettlement().setOrderNo(order.getOrderNo())
                .setPassengerId(order.getPassengerId()).setSettlementStatus(status)
                .setPaymentStatus(0).setManualActionRequired(manual)
                .setCreatedAt(now).setUpdatedAt(now);
    }
}
