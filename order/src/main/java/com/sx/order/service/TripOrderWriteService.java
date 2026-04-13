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
import com.sx.order.model.dto.OpenDriverOfferBody;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@Slf4j
public class TripOrderWriteService {

    private static final int STATUS_CREATED = 0;
    private static final int STATUS_ASSIGNED = 1;
    private static final int STATUS_ACCEPTED = 2;
    private static final int STATUS_ARRIVED = 3;
    private static final int STATUS_STARTED = 4;
    private static final int STATUS_FINISHED = 5;
    private static final int STATUS_CANCELLED = 6;
    /** 待司机在 offer 窗口内确认（派单确认） */
    private static final int STATUS_PENDING_DRIVER_CONFIRM = 7;
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

        log.info("order created orderNo={} passengerId={} cityCode={}", orderNo, body.getPassengerId(), body.getCityCode());
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
        log.info("order assigned orderNo={} driverId={}", orderNo, body.getDriverId());
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
            log.info("order cancel skipped (already cancelled) orderNo={}", orderNo);
            return;
        }
        if (st == null || (st != STATUS_CREATED && st != STATUS_ASSIGNED && st != STATUS_PENDING_DRIVER_CONFIRM && st != STATUS_ACCEPTED)) {
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
                        .in(TripOrder::getStatus, STATUS_CREATED, STATUS_ASSIGNED, STATUS_PENDING_DRIVER_CONFIRM, STATUS_ACCEPTED));
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
        log.info("order cancelled by passenger orderNo={} passengerId={}", orderNo, body.getPassengerId());
    }

    /**
     * 指派给司机且仍为「待确认」的订单（派单轮询列表）：{@code ASSIGNED} 或 {@code PENDING_DRIVER_CONFIRM}。
     */
    public List<TripOrder> listAssignedToDriver(Long driverId) {
        if (driverId == null) {
            throw new IllegalArgumentException("driverId不能为空");
        }
        return tripOrderEntityMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getDriverId, driverId)
                .in(TripOrder::getStatus, STATUS_ASSIGNED, STATUS_PENDING_DRIVER_CONFIRM)
                .eq(TripOrder::getIsDeleted, 0)
                .orderByDesc(TripOrder::getAssignedAt));
    }

    /**
     * 进入「待司机确认」窗口：{@code ASSIGNED → PENDING_DRIVER_CONFIRM}，写入 offer 截止时间。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void openDriverOffer(String orderNo, OpenDriverOfferBody body) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("orderNo不能为空");
        }
        int seconds = body != null && body.getOfferSeconds() > 0 ? body.getOfferSeconds() : 10;

        TripOrder existing = tripOrderEntityMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getOrderNo, orderNo)
                .eq(TripOrder::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (existing == null) {
            throw new IllegalArgumentException("订单不存在");
        }
        if (!Objects.equals(existing.getStatus(), STATUS_ASSIGNED)) {
            throw new IllegalArgumentException("订单当前状态不允许进入待确认");
        }

        int nextRound = existing.getOfferRound() == null ? 1 : existing.getOfferRound() + 1;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expires = now.plusSeconds(seconds);

        int updated = tripOrderEntityMapper.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TripOrder>lambdaUpdate()
                        .set(TripOrder::getStatus, STATUS_PENDING_DRIVER_CONFIRM)
                        .set(TripOrder::getOfferExpiresAt, expires)
                        .set(TripOrder::getOfferRound, nextRound)
                        .set(TripOrder::getLastOfferAt, now)
                        .set(TripOrder::getUpdatedAt, now)
                        .eq(TripOrder::getOrderNo, orderNo)
                        .eq(TripOrder::getIsDeleted, 0)
                        .eq(TripOrder::getStatus, STATUS_ASSIGNED));
        if (updated != 1) {
            throw new IllegalArgumentException("进入待确认失败，请重试");
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
                .setEventType("ORDER_OFFER_OPENED")
                .setFromStatus(STATUS_ASSIGNED)
                .setToStatus(STATUS_PENDING_DRIVER_CONFIRM)
                .setOperatorType(OPERATOR_SYSTEM)
                .setOperatorId(null)
                .setReasonCode(null)
                .setReasonDesc("offerSeconds=" + seconds)
                .setEventPayload("{\"offerExpiresAt\":\"" + expires + "\",\"offerRound\":" + nextRound + "}")
                .setOccurredAt(now)
                .setCreatedAt(now);
        orderEventEntityMapper.insert(event);
        log.info("driver offer opened orderNo={} expiresAt={} round={}", orderNo, expires, nextRound);
    }

    /**
     * 调度扫描：确认窗口超时 → 回到 {@code ASSIGNED}，保留 {@code driver_id}。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public int timeoutPendingDriverOffers(LocalDateTime now) {
        List<TripOrder> due = tripOrderEntityMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getStatus, STATUS_PENDING_DRIVER_CONFIRM)
                .eq(TripOrder::getIsDeleted, 0)
                .isNotNull(TripOrder::getOfferExpiresAt)
                .lt(TripOrder::getOfferExpiresAt, now));
        int n = 0;
        for (TripOrder o : due) {
            try {
                timeoutOnePendingOffer(o.getOrderNo(), now);
                n++;
            } catch (RuntimeException ex) {
                log.warn("offer timeout skip orderNo={} reason={}", o.getOrderNo(), ex.toString());
            }
        }
        return n;
    }

    private void timeoutOnePendingOffer(String orderNo, LocalDateTime now) {
        TripOrder existing = tripOrderEntityMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getOrderNo, orderNo)
                .eq(TripOrder::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (existing == null || !Objects.equals(existing.getStatus(), STATUS_PENDING_DRIVER_CONFIRM)) {
            return;
        }
        if (existing.getOfferExpiresAt() == null || !existing.getOfferExpiresAt().isBefore(now)) {
            return;
        }

        int updated = tripOrderEntityMapper.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TripOrder>lambdaUpdate()
                        .set(TripOrder::getStatus, STATUS_ASSIGNED)
                        .set(TripOrder::getOfferExpiresAt, null)
                        .set(TripOrder::getUpdatedAt, now)
                        .eq(TripOrder::getOrderNo, orderNo)
                        .eq(TripOrder::getIsDeleted, 0)
                        .eq(TripOrder::getStatus, STATUS_PENDING_DRIVER_CONFIRM));
        if (updated != 1) {
            return;
        }

        TripOrder after = tripOrderEntityMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getOrderNo, orderNo)
                .eq(TripOrder::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (after == null) {
            return;
        }
        OrderEvent event = new OrderEvent()
                .setOrderId(after.getId())
                .setOrderNo(orderNo)
                .setEventType("ORDER_OFFER_TIMED_OUT")
                .setFromStatus(STATUS_PENDING_DRIVER_CONFIRM)
                .setToStatus(STATUS_ASSIGNED)
                .setOperatorType(OPERATOR_SYSTEM)
                .setOperatorId(null)
                .setReasonCode("OFFER_TIMEOUT")
                .setReasonDesc("driver_id retained for reschedule")
                .setEventPayload("{\"driverId\":" + after.getDriverId() + "}")
                .setOccurredAt(now)
                .setCreatedAt(now);
        orderEventEntityMapper.insert(event);
        log.info("driver offer timed out orderNo={} driverId={}", orderNo, after.getDriverId());
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
     * 司机接单：{@code ASSIGNED} 或 {@code PENDING_DRIVER_CONFIRM} → {@code ACCEPTED}（幂等：已是 ACCEPTED 则直接返回）。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void accept(String orderNo, Long driverId) {
        TripOrder existing = loadActiveOrder(orderNo);
        assertDriver(existing, driverId);
        Integer st = existing.getStatus();
        if (Objects.equals(st, STATUS_ACCEPTED)) {
            log.info("accept idempotent (already accepted) orderNo={} driverId={}", orderNo, driverId);
            return;
        }
        if (!Objects.equals(st, STATUS_ASSIGNED) && !Objects.equals(st, STATUS_PENDING_DRIVER_CONFIRM)) {
            throw new IllegalArgumentException("订单当前状态不允许接单");
        }

        LocalDateTime now = LocalDateTime.now();
        int fromStatus = st;
        int updated = tripOrderEntityMapper.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TripOrder>lambdaUpdate()
                        .set(TripOrder::getStatus, STATUS_ACCEPTED)
                        .set(TripOrder::getAcceptedAt, now)
                        .set(TripOrder::getUpdatedAt, now)
                        .set(TripOrder::getOfferExpiresAt, null)
                        .eq(TripOrder::getOrderNo, orderNo)
                        .eq(TripOrder::getIsDeleted, 0)
                        .in(TripOrder::getStatus, STATUS_ASSIGNED, STATUS_PENDING_DRIVER_CONFIRM)
                        .eq(TripOrder::getDriverId, driverId));
        if (updated != 1) {
            throw new IllegalArgumentException("接单失败，请重试");
        }

        insertDriverEvent(orderNo, driverId, "ORDER_ACCEPTED", fromStatus, STATUS_ACCEPTED, now);
        log.info("order accepted orderNo={} driverId={}", orderNo, driverId);
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
            log.info("arrive idempotent orderNo={} driverId={}", orderNo, driverId);
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
        log.info("driver arrived orderNo={} driverId={}", orderNo, driverId);
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
            log.info("start trip idempotent orderNo={} driverId={}", orderNo, driverId);
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
        log.info("trip started orderNo={} driverId={}", orderNo, driverId);
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
            log.info("finish idempotent orderNo={} driverId={}", orderNo, driverId);
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
        log.info("order finished orderNo={} driverId={} finalAmount={}", orderNo, driverId, finalAmount);
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

