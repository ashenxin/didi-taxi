package com.sx.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.order.dao.OrderEventEntityMapper;
import com.sx.order.dao.OrderIdempotentRecordMapper;
import com.sx.order.dao.OrderOutboxEventMapper;
import com.sx.order.dao.TripOrderEntityMapper;
import com.sx.order.dao.TripOrderSettlementMapper;
import com.sx.order.model.OrderEvent;
import com.sx.order.model.OrderIdempotentRecord;
import com.sx.order.model.TripOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TripOrderDriverActionIdempotencyTest {

    private static final long DRIVER_ID = 88001L;

    @Autowired private MockMvc mockMvc;
    @Autowired private TripOrderEntityMapper orderMapper;
    @Autowired private OrderEventEntityMapper eventMapper;
    @Autowired private OrderOutboxEventMapper outboxMapper;
    @Autowired private OrderIdempotentRecordMapper idempotentMapper;
    @Autowired private TripOrderSettlementMapper settlementMapper;

    @BeforeEach
    void clean() {
        settlementMapper.delete(null);
        outboxMapper.delete(null);
        eventMapper.delete(null);
        orderMapper.delete(null);
        idempotentMapper.delete(null);
    }

    @Test
    void rejectReplayAfterLostResponseReturnsSuccessWithoutDuplicatingWrites() throws Exception {
        TripOrder order = driverOrder("REJECT-REPLAY", 98101L, 7);
        orderMapper.insert(order);

        performReject(order.getOrderNo(), "reject-key-1", "TOO_FAR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.replayed").value(false));

        performReject(order.getOrderNo(), "reject-key-1", "TOO_FAR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.replayed").value(true));

        assertSingleActionSideEffects(order.getOrderNo(), "ORDER_DRIVER_REJECTED", "DRIVER_REJECT_ORDER");
    }

    @Test
    void cancelReplayAfterLostResponseReturnsSuccessWithoutDuplicatingWrites() throws Exception {
        TripOrder order = driverOrder("CANCEL-REPLAY", 98102L, 2);
        orderMapper.insert(order);

        performCancel(order.getOrderNo(), "cancel-key-1", "VEHICLE_FAULT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.replayed").value(false));

        performCancel(order.getOrderNo(), "cancel-key-1", "VEHICLE_FAULT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.replayed").value(true));

        assertSingleActionSideEffects(order.getOrderNo(), "ORDER_DRIVER_CANCELLED_BEFORE_ARRIVE",
                "DRIVER_CANCEL_BEFORE_ARRIVE");
    }

    @Test
    void sameKeyWithDifferentDriverActionRequestReturnsConflict() throws Exception {
        TripOrder order = driverOrder("REJECT-CONFLICT", 98103L, 1);
        orderMapper.insert(order);

        performReject(order.getOrderNo(), "reject-key-conflict", "TOO_FAR")
                .andExpect(jsonPath("$.code").value(200));

        performReject(order.getOrderNo(), "reject-key-conflict", "OTHER_REASON")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.msg").value("同一 Idempotency-Key 不能用于不同操作内容"));
    }

    @Test
    void sameKeyCannotBeReusedAcrossRejectAndCancel() throws Exception {
        TripOrder rejected = driverOrder("REJECT-ACTION", 98104L, 1);
        orderMapper.insert(rejected);
        performReject(rejected.getOrderNo(), "cross-action-key", "TOO_FAR")
                .andExpect(jsonPath("$.code").value(200));

        TripOrder cancelled = driverOrder("CANCEL-ACTION", 98105L, 2);
        orderMapper.insert(cancelled);
        performCancel(cancelled.getOrderNo(), "cross-action-key", "VEHICLE_FAULT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.msg").value("同一 Idempotency-Key 已用于其它操作"));

        assertThat(orderMapper.selectOne(Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getOrderNo, cancelled.getOrderNo())).getStatus()).isEqualTo(2);
    }

    @Test
    void passengerCancelReplayReturnsSuccessAndPreservesOriginalReason() throws Exception {
        TripOrder order = driverOrder("PASSENGER-CANCEL", 98106L, 0).setDriverId(null);
        orderMapper.insert(order);

        performPassengerCancel(order.getOrderNo(), "passenger-cancel-key", 98106L, "计划有变")
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.replayed").value(false));
        performPassengerCancel(order.getOrderNo(), "passenger-cancel-key", 98106L, "计划有变")
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.replayed").value(true));

        TripOrder after = findOrder(order.getOrderNo());
        assertThat(after.getCancelReason()).isEqualTo("计划有变");
        assertThat(eventCount(order.getOrderNo(), "ORDER_CANCELLED")).isEqualTo(1);
    }

    @Test
    void passengerCancelSameKeyWithDifferentReasonReturnsConflict() throws Exception {
        TripOrder order = driverOrder("PASSENGER-CANCEL-CONFLICT", 98107L, 0).setDriverId(null);
        orderMapper.insert(order);

        performPassengerCancel(order.getOrderNo(), "passenger-cancel-conflict", 98107L, "计划有变")
                .andExpect(jsonPath("$.code").value(200));
        performPassengerCancel(order.getOrderNo(), "passenger-cancel-conflict", 98107L, "叫错车了")
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.msg").value("同一 Idempotency-Key 不能用于不同操作内容"));
    }

    @Test
    void acceptReplaySucceedsEvenAfterOrderProgressedToArrived() throws Exception {
        TripOrder order = driverOrder("ACCEPT-LATE-REPLAY", 98108L, 7);
        orderMapper.insert(order);

        performDriverAction(order.getOrderNo(), "accept", "accept-key", "{\"driverId\":88001}")
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.replayed").value(false));
        updateStatus(order.getOrderNo(), 3);
        performDriverAction(order.getOrderNo(), "accept-preflight", "accept-key", "{\"driverId\":88001}")
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.replayed").value(true));
        performDriverAction(order.getOrderNo(), "accept", "accept-key", "{\"driverId\":88001}")
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.replayed").value(true));

        assertThat(eventCount(order.getOrderNo(), "ORDER_ACCEPTED")).isEqualTo(1);
    }

    @Test
    void arriveReplaySucceedsEvenAfterOrderProgressedToStarted() throws Exception {
        TripOrder order = driverOrder("ARRIVE-LATE-REPLAY", 98109L, 2);
        orderMapper.insert(order);

        performDriverAction(order.getOrderNo(), "arrive", "arrive-key", "{\"driverId\":88001}")
                .andExpect(jsonPath("$.data.replayed").value(false));
        updateStatus(order.getOrderNo(), 4);
        performDriverAction(order.getOrderNo(), "arrive", "arrive-key", "{\"driverId\":88001}")
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.replayed").value(true));

        assertThat(eventCount(order.getOrderNo(), "ORDER_DRIVER_ARRIVED")).isEqualTo(1);
    }

    @Test
    void startReplaySucceedsEvenAfterOrderProgressedToFinished() throws Exception {
        TripOrder order = driverOrder("START-LATE-REPLAY", 98110L, 3);
        orderMapper.insert(order);

        performDriverAction(order.getOrderNo(), "start", "start-key", "{\"driverId\":88001}")
                .andExpect(jsonPath("$.data.replayed").value(false));
        updateStatus(order.getOrderNo(), 5);
        performDriverAction(order.getOrderNo(), "start", "start-key", "{\"driverId\":88001}")
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.replayed").value(true));

        assertThat(eventCount(order.getOrderNo(), "ORDER_TRIP_STARTED")).isEqualTo(1);
    }

    @Test
    void finishReplayDoesNotDuplicateSettlementEventOrOutbox() throws Exception {
        TripOrder order = driverOrder("FINISH-REPLAY", 98111L, 4)
                .setEstimatedAmount(new BigDecimal("35.00"));
        orderMapper.insert(order);

        performDriverAction(order.getOrderNo(), "finish", "finish-key",
                "{\"driverId\":88001,\"distanceKm\":12.3,\"durationMin\":31,\"finalAmount\":0.01}")
                .andExpect(jsonPath("$.data.replayed").value(false));
        performDriverAction(order.getOrderNo(), "finish", "finish-key",
                "{\"driverId\":88001,\"distanceKm\":99.9,\"durationMin\":999,\"finalAmount\":999.99}")
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.replayed").value(true));

        assertThat(settlementMapper.selectCount(null)).isEqualTo(1);
        assertThat(eventCount(order.getOrderNo(), "ORDER_FINISHED")).isEqualTo(1);
        assertThat(outboxMapper.selectCount(Wrappers.lambdaQuery())).isEqualTo(2);
    }

    private org.springframework.test.web.servlet.ResultActions performReject(
            String orderNo, String key, String reasonCode) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/{orderNo}/reject", orderNo)
                .header("X-User-Id", String.valueOf(DRIVER_ID))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"driverId\":" + DRIVER_ID + ",\"reasonCode\":\"" + reasonCode + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions performCancel(
            String orderNo, String key, String reasonCode) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/{orderNo}/driver/cancel", orderNo)
                .header("X-User-Id", String.valueOf(DRIVER_ID))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"driverId\":" + DRIVER_ID + ",\"reasonCode\":\"" + reasonCode + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions performPassengerCancel(
            String orderNo, String key, long passengerId, String reason) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/{orderNo}/cancel", orderNo)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"passengerId\":" + passengerId + ",\"cancelReason\":\"" + reason + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions performDriverAction(
            String orderNo, String action, String key, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/{orderNo}/{action}", orderNo, action)
                .header("X-User-Id", String.valueOf(DRIVER_ID))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void updateStatus(String orderNo, int status) {
        TripOrder order = findOrder(orderNo);
        order.setStatus(status).setUpdatedAt(LocalDateTime.now());
        orderMapper.updateById(order);
    }

    private TripOrder findOrder(String orderNo) {
        return orderMapper.selectOne(Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getOrderNo, orderNo));
    }

    private long eventCount(String orderNo, String eventType) {
        return eventMapper.selectCount(Wrappers.<OrderEvent>lambdaQuery()
                .eq(OrderEvent::getOrderNo, orderNo)
                .eq(OrderEvent::getEventType, eventType));
    }

    private void assertSingleActionSideEffects(String orderNo, String eventType, String actionType) {
        TripOrder after = orderMapper.selectOne(Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getOrderNo, orderNo));
        assertThat(after.getStatus()).isZero();
        assertThat(after.getDriverId()).isNull();
        assertThat(eventMapper.selectCount(Wrappers.<OrderEvent>lambdaQuery()
                .eq(OrderEvent::getOrderNo, orderNo)
                .eq(OrderEvent::getEventType, eventType))).isEqualTo(1);
        assertThat(outboxMapper.selectCount(Wrappers.lambdaQuery())).isEqualTo(1);
        OrderIdempotentRecord record = idempotentMapper.selectOne(Wrappers.<OrderIdempotentRecord>lambdaQuery()
                .eq(OrderIdempotentRecord::getRequestId,
                        actionType.equals("DRIVER_REJECT_ORDER") ? "reject-key-1" : "cancel-key-1"));
        assertThat(record.getActionType()).isEqualTo(actionType);
        assertThat(record.getStatus()).isEqualTo("SUCCESS");
        assertThat(record.getOrderNo()).isEqualTo(orderNo);
    }

    private static TripOrder driverOrder(String orderNo, long passengerId, int status) {
        LocalDateTime now = LocalDateTime.now();
        return new TripOrder().setOrderNo(orderNo).setPassengerId(passengerId)
                .setDriverId(DRIVER_ID).setCarId(77001L).setCompanyId(66001L)
                .setProductCode("ECONOMY").setProvinceCode("330000").setCityCode("330100")
                .setOriginAddress("起点").setOriginLat(new BigDecimal("30.1"))
                .setOriginLng(new BigDecimal("120.1"))
                .setDestAddress("终点").setDestLat(new BigDecimal("30.2"))
                .setDestLng(new BigDecimal("120.2"))
                .setStatus(status).setBlocksNewOrder(1).setOfferRound(1)
                .setAssignedAt(now).setOfferExpiresAt(now.plusSeconds(30))
                .setAcceptedAt(status == 2 ? now : null)
                .setCreatedAt(now).setUpdatedAt(now).setIsDeleted(0);
    }
}
