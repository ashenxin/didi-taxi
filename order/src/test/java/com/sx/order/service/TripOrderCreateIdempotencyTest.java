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
import com.sx.order.model.dto.Place;
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

    @BeforeEach
    void clean() {
        outboxMapper.delete(null);
        eventMapper.delete(null);
        tripOrderMapper.delete(null);
        idempotentMapper.delete(null);
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
}
