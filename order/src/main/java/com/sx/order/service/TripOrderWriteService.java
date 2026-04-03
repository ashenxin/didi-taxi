package com.sx.order.service;

import com.sx.order.common.util.OrderNoUtil;
import com.sx.order.dao.OrderEventEntityMapper;
import com.sx.order.dao.TripOrderEntityMapper;
import com.sx.order.model.OrderEvent;
import com.sx.order.model.TripOrder;
import com.sx.order.model.dto.AssignOrderBody;
import com.sx.order.model.dto.CancelOrderBody;
import com.sx.order.model.dto.CreateOrderBody;
import com.sx.order.model.dto.FinishOrderBody;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class TripOrderWriteService {

    private static final int STATUS_CREATED = 0;
    private static final int STATUS_ASSIGNED = 1;
    private static final int STATUS_ACCEPTED = 2;
    private static final int STATUS_ARRIVED = 3;
    private static final int STATUS_STARTED = 4;
    private static final int STATUS_FINISHED = 5;
    private static final int STATUS_CANCELLED = 6;
    private static final int OPERATOR_PASSENGER = 1;
    private static final int OPERATOR_DRIVER = 2;
    private static final int OPERATOR_SYSTEM = 0;
    private static final int CANCEL_BY_PASSENGER = 1;

    private final TripOrderEntityMapper tripOrderEntityMapper;
    private final OrderEventEntityMapper orderEventEntityMapper;

    public TripOrderWriteService(TripOrderEntityMapper tripOrderEntityMapper, OrderEventEntityMapper orderEventEntityMapper) {
        this.tripOrderEntityMapper = tripOrderEntityMapper;
        this.orderEventEntityMapper = orderEventEntityMapper;
    }

    /**
     * 创建订单：落库 trip_order(status=CREATED) + 写 order_event(ORDER_CREATED)。
     */
    @Transactional
    public String create(CreateOrderBody body) {
        LocalDateTime now = LocalDateTime.now();
        String orderNo = OrderNoUtil.nextOrderNo();

        TripOrder order = new TripOrder()
                .setOrderNo(orderNo)
                .setPassengerId(body.getPassengerId())
                .setProductCode(body.getProductCode())
                .setProvinceCode(body.getProvinceCode())
                .setCityCode(body.getCityCode())
                .setOriginAddress(body.getOrigin().getAddress())
                .setOriginLat(body.getOrigin().getLat())
                .setOriginLng(body.getOrigin().getLng())
                .setDestAddress(body.getDest().getAddress())
                .setDestLat(body.getDest().getLat())
                .setDestLng(body.getDest().getLng())
                .setStatus(STATUS_CREATED)
                .setEstimatedAmount(body.getEstimatedAmount())
                .setFareRuleId(body.getFareRuleId())
                .setFareRuleSnapshot(body.getFareRuleSnapshot())
                .setCreatedAt(now)
                .setUpdatedAt(now)
                .setIsDeleted(0);

        tripOrderEntityMapper.insert(order);

        OrderEvent event = new OrderEvent()
                .setOrderId(order.getId())
                .setOrderNo(orderNo)
                .setEventType("ORDER_CREATED")
                .setFromStatus(null)
                .setToStatus(STATUS_CREATED)
                .setOperatorType(OPERATOR_PASSENGER)
                .setOperatorId(body.getPassengerId())
                .setReasonCode(null)
                .setReasonDesc(null)
                .setEventPayload("{}")
                .setOccurredAt(now)
                .setCreatedAt(now);
        orderEventEntityMapper.insert(event);

        return orderNo;
    }

    /**
     * 指派司机：CAS 更新 CREATED -> ASSIGNED + 写事件 ORDER_ASSIGNED。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void assign(String orderNo, AssignOrderBody body) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("orderNo不能为空");
        }

        TripOrder existing = tripOrderEntityMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getOrderNo, orderNo)
                .eq(TripOrder::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (existing == null) {
            throw new IllegalArgumentException("订单不存在");
        }
        if (!Objects.equals(existing.getStatus(), STATUS_CREATED)) {
            throw new IllegalArgumentException("订单当前状态不允许指派");
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = tripOrderEntityMapper.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TripOrder>lambdaUpdate()
                        .set(TripOrder::getDriverId, body.getDriverId())
                        .set(body.getCarId() != null, TripOrder::getCarId, body.getCarId())
                        .set(body.getCompanyId() != null, TripOrder::getCompanyId, body.getCompanyId())
                        .set(TripOrder::getStatus, STATUS_ASSIGNED)
                        .set(TripOrder::getAssignedAt, now)
                        .set(TripOrder::getUpdatedAt, now)
                        .eq(TripOrder::getOrderNo, orderNo)
                        .eq(TripOrder::getIsDeleted, 0)
                        .eq(TripOrder::getStatus, STATUS_CREATED));
        if (updated != 1) {
            throw new IllegalArgumentException("指派失败，请重试");
        }

        TripOrder after = tripOrderEntityMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getOrderNo, orderNo)
                .eq(TripOrder::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (after == null) {
            throw new IllegalArgumentException("订单不存在");
        }

        OrderEvent event = new OrderEvent()
                .setOrderId(after.getId())
                .setOrderNo(orderNo)
                .setEventType("ORDER_ASSIGNED")
                .setFromStatus(STATUS_CREATED)
                .setToStatus(STATUS_ASSIGNED)
                .setOperatorType(OPERATOR_SYSTEM)
                .setOperatorId(null)
                .setReasonCode(null)
                .setReasonDesc(null)
                .setEventPayload("{}")
                .setOccurredAt(now)
                .setCreatedAt(now);
        orderEventEntityMapper.insert(event);
    }

    /**
     * 乘客取消订单：仅允许 CREATED/ASSIGNED/ACCEPTED → CANCELLED，需校验 passengerId。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void cancelByPassenger(String orderNo, CancelOrderBody body) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("orderNo不能为空");
        }
        TripOrder existing = tripOrderEntityMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getOrderNo, orderNo)
                .eq(TripOrder::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (existing == null) {
            throw new IllegalArgumentException("订单不存在");
        }
        if (!Objects.equals(existing.getPassengerId(), body.getPassengerId())) {
            throw new IllegalArgumentException("无权操作该订单");
        }
        Integer st = existing.getStatus();
        if (Objects.equals(st, STATUS_CANCELLED)) {
            return;
        }
        if (st == null || (st != STATUS_CREATED && st != STATUS_ASSIGNED && st != STATUS_ACCEPTED)) {
            throw new IllegalArgumentException("订单当前状态不允许取消");
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = tripOrderEntityMapper.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TripOrder>lambdaUpdate()
                        .set(TripOrder::getStatus, STATUS_CANCELLED)
                        .set(TripOrder::getCancelBy, CANCEL_BY_PASSENGER)
                        .set(TripOrder::getCancelReason, body.getCancelReason())
                        .set(TripOrder::getCancelledAt, now)
                        .set(TripOrder::getUpdatedAt, now)
                        .eq(TripOrder::getOrderNo, orderNo)
                        .eq(TripOrder::getIsDeleted, 0)
                        .in(TripOrder::getStatus, STATUS_CREATED, STATUS_ASSIGNED, STATUS_ACCEPTED));
        if (updated != 1) {
            throw new IllegalArgumentException("取消失败，请重试");
        }

        TripOrder after = tripOrderEntityMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getOrderNo, orderNo)
                .eq(TripOrder::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (after == null) {
            throw new IllegalArgumentException("订单不存在");
        }

        OrderEvent event = new OrderEvent()
                .setOrderId(after.getId())
                .setOrderNo(orderNo)
                .setEventType("ORDER_CANCELLED")
                .setFromStatus(st)
                .setToStatus(STATUS_CANCELLED)
                .setOperatorType(OPERATOR_PASSENGER)
                .setOperatorId(body.getPassengerId())
                .setReasonCode(null)
                .setReasonDesc(body.getCancelReason())
                .setEventPayload("{}")
                .setOccurredAt(now)
                .setCreatedAt(now);
        orderEventEntityMapper.insert(event);
    }

    /**
     * 指派给司机且仍为「待确认」的订单（派单轮询列表）。
     */
    public List<TripOrder> listAssignedToDriver(Long driverId) {
        if (driverId == null) {
            throw new IllegalArgumentException("driverId不能为空");
        }
        return tripOrderEntityMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getDriverId, driverId)
                .eq(TripOrder::getStatus, STATUS_ASSIGNED)
                .eq(TripOrder::getIsDeleted, 0)
                .orderByDesc(TripOrder::getAssignedAt));
    }

    private TripOrder loadActiveOrder(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("orderNo不能为空");
        }
        TripOrder existing = tripOrderEntityMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getOrderNo, orderNo)
                .eq(TripOrder::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (existing == null) {
            throw new IllegalArgumentException("订单不存在");
        }
        return existing;
    }

    private static void assertDriver(TripOrder order, Long driverId) {
        if (driverId == null) {
            throw new IllegalArgumentException("driverId不能为空");
        }
        if (!Objects.equals(order.getDriverId(), driverId)) {
            throw new IllegalArgumentException("非本单指派司机");
        }
    }

    /**
     * 司机接单：ASSIGNED → ACCEPTED（幂等：已是 ACCEPTED 且司机一致则直接返回）。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void accept(String orderNo, Long driverId) {
        TripOrder existing = loadActiveOrder(orderNo);
        assertDriver(existing, driverId);
        Integer st = existing.getStatus();
        if (Objects.equals(st, STATUS_ACCEPTED)) {
            return;
        }
        if (!Objects.equals(st, STATUS_ASSIGNED)) {
            throw new IllegalArgumentException("订单当前状态不允许接单");
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = tripOrderEntityMapper.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TripOrder>lambdaUpdate()
                        .set(TripOrder::getStatus, STATUS_ACCEPTED)
                        .set(TripOrder::getAcceptedAt, now)
                        .set(TripOrder::getUpdatedAt, now)
                        .eq(TripOrder::getOrderNo, orderNo)
                        .eq(TripOrder::getIsDeleted, 0)
                        .eq(TripOrder::getStatus, STATUS_ASSIGNED)
                        .eq(TripOrder::getDriverId, driverId));
        if (updated != 1) {
            throw new IllegalArgumentException("接单失败，请重试");
        }

        insertDriverEvent(orderNo, driverId, "ORDER_ACCEPTED", STATUS_ASSIGNED, STATUS_ACCEPTED, now);
    }

    /**
     * 到达上车点：ACCEPTED → ARRIVED。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void arrive(String orderNo, Long driverId) {
        TripOrder existing = loadActiveOrder(orderNo);
        assertDriver(existing, driverId);
        Integer st = existing.getStatus();
        if (Objects.equals(st, STATUS_ARRIVED)) {
            return;
        }
        if (!Objects.equals(st, STATUS_ACCEPTED)) {
            throw new IllegalArgumentException("订单当前状态不允许上报到达");
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = tripOrderEntityMapper.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TripOrder>lambdaUpdate()
                        .set(TripOrder::getStatus, STATUS_ARRIVED)
                        .set(TripOrder::getArrivedAt, now)
                        .set(TripOrder::getUpdatedAt, now)
                        .eq(TripOrder::getOrderNo, orderNo)
                        .eq(TripOrder::getIsDeleted, 0)
                        .eq(TripOrder::getStatus, STATUS_ACCEPTED)
                        .eq(TripOrder::getDriverId, driverId));
        if (updated != 1) {
            throw new IllegalArgumentException("到达确认失败，请重试");
        }

        insertDriverEvent(orderNo, driverId, "ORDER_DRIVER_ARRIVED", STATUS_ACCEPTED, STATUS_ARRIVED, now);
    }

    /**
     * 开始行程：ARRIVED → STARTED。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void start(String orderNo, Long driverId) {
        TripOrder existing = loadActiveOrder(orderNo);
        assertDriver(existing, driverId);
        Integer st = existing.getStatus();
        if (Objects.equals(st, STATUS_STARTED)) {
            return;
        }
        if (!Objects.equals(st, STATUS_ARRIVED)) {
            throw new IllegalArgumentException("订单当前状态不允许开始行程");
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = tripOrderEntityMapper.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TripOrder>lambdaUpdate()
                        .set(TripOrder::getStatus, STATUS_STARTED)
                        .set(TripOrder::getStartedAt, now)
                        .set(TripOrder::getUpdatedAt, now)
                        .eq(TripOrder::getOrderNo, orderNo)
                        .eq(TripOrder::getIsDeleted, 0)
                        .eq(TripOrder::getStatus, STATUS_ARRIVED)
                        .eq(TripOrder::getDriverId, driverId));
        if (updated != 1) {
            throw new IllegalArgumentException("开始行程失败，请重试");
        }

        insertDriverEvent(orderNo, driverId, "ORDER_TRIP_STARTED", STATUS_ARRIVED, STATUS_STARTED, now);
    }

    /**
     * 完单：STARTED → FINISHED，写入 {@code final_amount}（未传则暂用 {@code estimated_amount}）。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void finish(String orderNo, FinishOrderBody body) {
        Long driverId = body.getDriverId();
        TripOrder existing = loadActiveOrder(orderNo);
        assertDriver(existing, driverId);
        Integer st = existing.getStatus();
        if (Objects.equals(st, STATUS_FINISHED)) {
            return;
        }
        if (!Objects.equals(st, STATUS_STARTED)) {
            throw new IllegalArgumentException("订单当前状态不允许完单");
        }

        BigDecimal finalAmount = body.getFinalAmount();
        if (finalAmount == null) {
            finalAmount = existing.getEstimatedAmount();
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = tripOrderEntityMapper.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TripOrder>lambdaUpdate()
                        .set(TripOrder::getStatus, STATUS_FINISHED)
                        .set(TripOrder::getFinalAmount, finalAmount)
                        .set(TripOrder::getFinishedAt, now)
                        .set(TripOrder::getUpdatedAt, now)
                        .eq(TripOrder::getOrderNo, orderNo)
                        .eq(TripOrder::getIsDeleted, 0)
                        .eq(TripOrder::getStatus, STATUS_STARTED)
                        .eq(TripOrder::getDriverId, driverId));
        if (updated != 1) {
            throw new IllegalArgumentException("完单失败，请重试");
        }

        insertDriverEvent(orderNo, driverId, "ORDER_FINISHED", STATUS_STARTED, STATUS_FINISHED, now);
    }

    private void insertDriverEvent(String orderNo, Long driverId, String eventType,
                                   Integer fromStatus, Integer toStatus, LocalDateTime now) {
        TripOrder after = tripOrderEntityMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getOrderNo, orderNo)
                .eq(TripOrder::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (after == null) {
            throw new IllegalArgumentException("订单不存在");
        }
        OrderEvent event = new OrderEvent()
                .setOrderId(after.getId())
                .setOrderNo(orderNo)
                .setEventType(eventType)
                .setFromStatus(fromStatus)
                .setToStatus(toStatus)
                .setOperatorType(OPERATOR_DRIVER)
                .setOperatorId(driverId)
                .setReasonCode(null)
                .setReasonDesc(null)
                .setEventPayload("{}")
                .setOccurredAt(now)
                .setCreatedAt(now);
        orderEventEntityMapper.insert(event);
    }
}

