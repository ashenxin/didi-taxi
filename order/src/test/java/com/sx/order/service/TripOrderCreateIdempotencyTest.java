package com.sx.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.order.common.exception.OrderConflictException;
import com.sx.order.dao.OrderEventEntityMapper;
import com.sx.order.dao.OrderIdempotentRecordMapper;
import com.sx.order.dao.OrderOutboxEventMapper;
import com.sx.order.dao.TripOrderEntityMapper;
import com.sx.order.model.OrderIdempotentRecord;
import com.sx.order.model.TripOrder;
import com.sx.order.model.dto.CreateOrderBody;
import com.sx.order.model.dto.CreateOrderPreflightRequest;
import com.sx.order.model.dto.Place;
import com.sx.order.lifecycle.dao.OrderAccountLifecycleProjectionMapper;
import com.sx.order.lifecycle.dao.OrderAccountLifecycleEventInboxMapper;
import com.sx.order.lifecycle.service.OrderLifecycleProjectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TripOrderCreateIdempotencyTest {

    @Autowired
    private TripOrderWriteService service;
    @Autowired
    private TripOrderEntityMapper tripOrderMapper;
    @Autowired
    private OrderEventEntityMapper eventMapper;
    @Autowired
    private OrderOutboxEventMapper outboxMapper;
    @Autowired
    private OrderIdempotentRecordMapper idempotentMapper;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OrderLifecycleProjectionService lifecycleProjectionService;
    @Autowired
    private OrderAccountLifecycleProjectionMapper lifecycleProjectionMapper;
    @Autowired
    private OrderAccountLifecycleEventInboxMapper lifecycleEventInboxMapper;

    @BeforeEach
    void clean() {
        outboxMapper.delete(null);
        eventMapper.delete(null);
        tripOrderMapper.delete(null);
        idempotentMapper.delete(null);
        lifecycleProjectionMapper.delete(null);
        lifecycleEventInboxMapper.delete(null);
        for (long customerId = 90001L; customerId <= 90009L; customerId++) {
            seedActive(customerId);
        }
    }

    @Test
    void sameKeyAndSameBodyReturnsSameOrderWithoutDuplicatingSideEffects() {
        CreateOrderBody body = body(90001L, "ECONOMY");

        String first = service.create(body, "idem-create-1");
        String second = service.create(body, "idem-create-1");

        assertThat(second).isEqualTo(first);
        assertThat(tripOrderMapper.selectCount(null)).isEqualTo(1);
        assertThat(eventMapper.selectCount(null)).isEqualTo(1);
        assertThat(outboxMapper.selectCount(null)).isEqualTo(1);
        OrderIdempotentRecord record = idempotentMapper.selectOne(Wrappers.<OrderIdempotentRecord>lambdaQuery()
                .eq(OrderIdempotentRecord::getRequestId, "idem-create-1"));
        assertThat(record.getStatus()).isEqualTo("SUCCESS");
        assertThat(record.getOrderNo()).isEqualTo(first);
        assertThat(record.getResponseSnapshot()).contains(first);
        TripOrder order = tripOrderMapper.selectOne(Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getOrderNo, first));
        assertThat(order.getPlannedDistanceMeters()).isEqualTo(12_340L);
        assertThat(order.getPlannedDurationSeconds()).isEqualTo(1_560L);
        assertThat(order.getDistanceSource()).isEqualTo("LOCAL_MOCK_ROUTE");
        assertThat(order.getRouteMockVersion()).isEqualTo("mock-route-v1");
        assertThat(order.getFareCalculationVersion()).isEqualTo("fare-v1");
        assertThat(order.getFareRuleSnapshot()).contains("baseFare");
        assertThat(order.getBlocksNewOrder()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyReturnsConflict() {
        String key = "idem-create-different";
        service.create(body(90002L, "ECONOMY"), key);

        assertThatThrownBy(() -> service.create(body(90002L, "PREMIUM"), key))
                .isInstanceOf(OrderConflictException.class)
                .hasMessageContaining("不能用于不同下单内容");
    }

    @Test
    void frozenPricingInputsParticipateInIdempotencyHash() {
        String key = "idem-pricing-snapshot";
        CreateOrderBody first = body(90005L, "ECONOMY");
        CreateOrderBody changed = body(90005L, "ECONOMY");
        changed.setPlannedDistanceMeters(12_341L);

        service.create(first, key);

        assertThatThrownBy(() -> service.create(changed, key))
                .isInstanceOf(OrderConflictException.class)
                .hasMessageContaining("不能用于不同下单内容");
    }

    @Test
    void processingRecordReturnsConflictWithoutCreatingOrder() {
        idempotentMapper.insert(new OrderIdempotentRecord()
                .setRequestId("idem-processing")
                .setActionType("CREATE_ORDER")
                .setPassengerId(90003L)
                .setStatus("PROCESSING")
                .setRequestHash(hashFor(body(90003L, "ECONOMY")))
                .setCreatedAt(LocalDateTime.now())
                .setUpdatedAt(LocalDateTime.now()));

        assertThatThrownBy(() -> service.create(body(90003L, "ECONOMY"), "idem-processing"))
                .isInstanceOf(OrderConflictException.class)
                .hasMessageContaining("请求处理中");
        assertThat(tripOrderMapper.selectCount(null)).isZero();
        assertThat(outboxMapper.selectCount(null)).isZero();
    }

    @Test
    void idempotentReplayWinsOverActiveOrderConflict() {
        CreateOrderBody body = body(90004L, "ECONOMY");
        String first = service.create(body, "idem-active-order");

        String second = service.create(body, "idem-active-order");

        assertThat(second).isEqualTo(first);
    }

    @Test
    void successfulCreatePreflightReturnsFrozenReplayWithoutStartingAnotherCreate() throws Exception {
        CreateOrderBody original = body(90006L, "ECONOMY");
        String orderNo = service.create(original, "idem-preflight-replay");

        mockMvc.perform(post("/api/v1/orders/internal/create-preflight")
                        .header("Idempotency-Key", "idem-preflight-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "passengerId": 90006,
                                  "provinceCode": "330000",
                                  "cityCode": "330100",
                                  "productCode": "ECONOMY",
                                  "origin": {"address": "杭州东站", "lat": 30.2912000, "lng": 120.2120000},
                                  "dest": {"address": "龙翔桥", "lat": 30.2592000, "lng": 120.1640000}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.decision").value("REPLAY_SUCCESS"))
                .andExpect(jsonPath("$.data.orderNo").value(orderNo))
                .andExpect(jsonPath("$.data.plannedDistanceMeters").value(12340))
                .andExpect(jsonPath("$.data.plannedDurationSeconds").value(1560))
                .andExpect(jsonPath("$.data.distanceSource").value("LOCAL_MOCK_ROUTE"))
                .andExpect(jsonPath("$.data.routeMockVersion").value("mock-route-v1"))
                .andExpect(jsonPath("$.data.estimatedAmount").value(35.00))
                .andExpect(jsonPath("$.data.fareRuleId").value(1))
                .andExpect(jsonPath("$.data.fareCalculationVersion").value("fare-v1"));
    }

    @Test
    void preflightAllowsNewCreateWhenKeyAndBlockingOrderAreAbsent() {
        var result = service.preflightCreate(preflightRequest(body(90007L, "ECONOMY")), "new-key");

        assertThat(result.decision()).isEqualTo("ALLOW_CREATE");
        assertThat(result.orderNo()).isNull();
    }

    @Test
    void preflightBlocksNewKeyWhenPassengerAlreadyHasActiveOrder() {
        CreateOrderBody original = body(90008L, "ECONOMY");
        String orderNo = service.create(original, "original-key");

        var result = service.preflightCreate(preflightRequest(original), "another-key");

        assertThat(result.decision()).isEqualTo("BLOCKED");
        assertThat(result.orderNo()).isEqualTo(orderNo);
        assertThat(result.blockingAction()).isEqualTo("WAIT");
    }

    @Test
    void preflightRejectsSameKeyWhenOriginalBookingIntentChanged() {
        CreateOrderBody original = body(90009L, "ECONOMY");
        service.create(original, "changed-intent-key");
        CreateOrderBody changed = body(90009L, "PREMIUM");

        assertThatThrownBy(() -> service.preflightCreate(
                preflightRequest(changed), "changed-intent-key"))
                .isInstanceOf(OrderConflictException.class)
                .hasMessageContaining("不能用于不同下单内容");
    }

    private String hashFor(CreateOrderBody body) {
        String orderNo = service.create(body, "hash-source");
        OrderIdempotentRecord record = idempotentMapper.selectOne(Wrappers.<OrderIdempotentRecord>lambdaQuery()
                .eq(OrderIdempotentRecord::getRequestId, "hash-source"));
        outboxMapper.delete(null);
        eventMapper.delete(null);
        tripOrderMapper.delete(Wrappers.<com.sx.order.model.TripOrder>lambdaQuery()
                .eq(com.sx.order.model.TripOrder::getOrderNo, orderNo));
        idempotentMapper.delete(Wrappers.<OrderIdempotentRecord>lambdaQuery()
                .eq(OrderIdempotentRecord::getRequestId, "hash-source"));
        return record.getRequestHash();
    }

    private static CreateOrderPreflightRequest preflightRequest(CreateOrderBody body) {
        return new CreateOrderPreflightRequest(body.getPassengerId(), body.getProvinceCode(),
                body.getCityCode(), body.getProductCode(), body.getOrigin(), body.getDest());
    }

    private static CreateOrderBody body(Long passengerId, String productCode) {
        CreateOrderBody body = new CreateOrderBody();
        body.setPassengerId(passengerId);
        body.setProvinceCode("330000");
        body.setCityCode("330100");
        body.setProductCode(productCode);
        body.setOrigin(place("杭州东站", "30.2912000", "120.2120000"));
        body.setDest(place("龙翔桥", "30.2592000", "120.1640000"));
        body.setEstimatedAmount(new BigDecimal("35.00"));
        body.setFareRuleId(1L);
        body.setFareRuleSnapshot("{\"baseFare\":12.00}");
        body.setFareCalculationVersion("fare-v1");
        body.setPlannedDistanceMeters(12_340L);
        body.setPlannedDurationSeconds(1_560L);
        body.setDistanceSource("LOCAL_MOCK_ROUTE");
        body.setRouteMockVersion("mock-route-v1");
        return body;
    }

    private static Place place(String address, String lat, String lng) {
        Place p = new Place();
        p.setAddress(address);
        p.setLat(new BigDecimal(lat));
        p.setLng(new BigDecimal(lng));
        return p;
    }

    private void seedActive(long customerId) {
        lifecycleProjectionService.seedActive(customerId,
                "test-seed-" + customerId, LocalDateTime.now());
    }
}
