package com.sx.order.service;

import com.sx.order.common.exception.OrderConflictException;
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
import com.sx.order.model.dto.PendingDispatchOrderDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

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
    /** 与表注释一致：系统取消（如司机已接其他单） */
    private static final int CANCEL_BY_SYSTEM = 3;

    /**
     * 系统取消原因：待派单超时（order-service 定时任务），与 passenger 提示一致。
     */
    public static final String CANCEL_REASON_DISPATCH_TIMEOUT = "待派单超时无可用车辆，请稍后重试";

    private final TripOrderEntityMapper tripOrderEntityMapper;
    private final OrderEventEntityMapper orderEventEntityMapper;

    public TripOrderWriteService(TripOrderEntityMapper tripOrderEntityMapper, OrderEventEntityMapper orderEventEntityMapper) {
        this.tripOrderEntityMapper = tripOrderEntityMapper;
        this.orderEventEntityMapper = orderEventEntityMapper;
    }

    /**
     * 创建订单：落库 trip_order(status=CREATED) + 写 order_event(ORDER_CREATED)。
     * 同一乘客若存在未删除且状态非「已完单 / 已取消」的订单，则拒绝创建（{@link OrderConflictException}）。
     */
    @Transactional
    public String create(CreateOrderBody body) {
        //查看是否有进行中的订单
        assertNoActiveOrderForPassenger(body.getPassengerId());

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
     * 进行中：除 {@link #STATUS_FINISHED}、{@link #STATUS_CANCELLED} 外均视为进行中（含 CREATED、派单、行程中等）。
     */
    private void assertNoActiveOrderForPassenger(Long passengerId) {
        if (passengerId == null) {
            throw new IllegalArgumentException("passengerId不能为空");
        }
        Long cnt = tripOrderEntityMapper.selectCount(Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getPassengerId, passengerId)
                .eq(TripOrder::getIsDeleted, 0)
                .notIn(TripOrder::getStatus, STATUS_FINISHED, STATUS_CANCELLED));
        if (cnt != null && cnt > 0) {
            throw new OrderConflictException("您已有进行中的订单，请先完成或取消后再下单");
        }
    }

    /**
     * 指派司机：CAS 更新 CREATED -> ASSIGNED + 写事件 ORDER_ASSIGNED。
     * 若司机已有行程中订单（{@link #STATUS_ACCEPTED}～{@link #STATUS_STARTED}），则拒绝再派单。
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

        assertDriverNotInServiceTrip(body.getDriverId());

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
     * 司机是否处于「服务中」：已接单～行程中（含到达），此期间不可再被派新单。
     */
    private void assertDriverNotInServiceTrip(Long driverId) {
        if (driverId == null) {
            throw new IllegalArgumentException("driverId不能为空");
        }
        Long cnt = tripOrderEntityMapper.selectCount(Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getDriverId, driverId)
                .eq(TripOrder::getIsDeleted, 0)
                .in(TripOrder::getStatus, STATUS_ACCEPTED, STATUS_ARRIVED, STATUS_STARTED));
        if (cnt != null && cnt > 0) {
            throw new OrderConflictException("司机正在服务中，无法指派新订单");
        }
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
     * 查询「待派单超时」候选：{@code CREATED} 且创建时间早于 deadline。
     */
    public List<TripOrder> listCreatedOlderThan(LocalDateTime deadline) {
        return tripOrderEntityMapper.selectList(Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getStatus, STATUS_CREATED)
                .eq(TripOrder::getIsDeleted, 0)
                .lt(TripOrder::getCreatedAt, deadline)
                .orderByAsc(TripOrder::getCreatedAt)
                .last("LIMIT 200"));
    }

    /**
     * 将单笔 {@code CREATED} 订单系统取消为 {@code CANCELLED}（待派单超时）；独立事务，供定时任务逐单调用。
     *
     * @return 是否本次成功更新一行
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean cancelCreatedDispatchTimeoutOne(String orderNo, LocalDateTime now) {
        if (orderNo == null || orderNo.isBlank()) {
            return false;
        }
        int updated = tripOrderEntityMapper.update(null,
                Wrappers.<TripOrder>lambdaUpdate()
                        .set(TripOrder::getStatus, STATUS_CANCELLED)
                        .set(TripOrder::getCancelBy, CANCEL_BY_SYSTEM)
                        .set(TripOrder::getCancelReason, CANCEL_REASON_DISPATCH_TIMEOUT)
                        .set(TripOrder::getCancelledAt, now)
                        .set(TripOrder::getUpdatedAt, now)
                        .eq(TripOrder::getOrderNo, orderNo)
                        .eq(TripOrder::getIsDeleted, 0)
                        .eq(TripOrder::getStatus, STATUS_CREATED));
        if (updated != 1) {
            return false;
        }
        TripOrder after = tripOrderEntityMapper.selectOne(Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getOrderNo, orderNo)
                .eq(TripOrder::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (after == null) {
            return false;
        }
        OrderEvent event = new OrderEvent()
                .setOrderId(after.getId())
                .setOrderNo(orderNo)
                .setEventType("ORDER_CANCELLED")
                .setFromStatus(STATUS_CREATED)
                .setToStatus(STATUS_CANCELLED)
                .setOperatorType(OPERATOR_SYSTEM)
                .setOperatorId(null)
                .setReasonCode("DISPATCH_TIMEOUT")
                .setReasonDesc(CANCEL_REASON_DISPATCH_TIMEOUT)
                .setEventPayload("{}")
                .setOccurredAt(now)
                .setCreatedAt(now);
        orderEventEntityMapper.insert(event);
        log.info("order cancelled by system (dispatch timeout) orderNo={}", orderNo);
        return true;
    }

    /**
     * 指派给司机且仍为「待确认」的订单（派单轮询列表）：{@code ASSIGNED} 或 {@code PENDING_DRIVER_CONFIRM}。
     * 已完单（{@link #STATUS_FINISHED}）、已取消（{@link #STATUS_CANCELLED}）及行程中状态均不在此列表。
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
     * 待派单队列：{@code CREATED} 且起点坐标已落库，供运力服务做迟滞匹配（按创建时间升序）。
     */
    public List<PendingDispatchOrderDto> listCreatedForDispatch(String cityCode, int limit) {
        if (cityCode == null || cityCode.isBlank()) {
            throw new IllegalArgumentException("cityCode不能为空");
        }
        int lim = (limit <= 0 || limit > 100) ? 50 : limit;
        List<TripOrder> list = tripOrderEntityMapper.selectList(Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getCityCode, cityCode)
                .eq(TripOrder::getStatus, STATUS_CREATED)
                .eq(TripOrder::getIsDeleted, 0)
                .isNotNull(TripOrder::getOriginLat)
                .isNotNull(TripOrder::getOriginLng)
                .orderByAsc(TripOrder::getCreatedAt)
                .last("LIMIT " + lim));
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return list.stream().map(this::toPendingDispatchDto).toList();
    }

    private PendingDispatchOrderDto toPendingDispatchDto(TripOrder o) {
        PendingDispatchOrderDto d = new PendingDispatchOrderDto();
        d.setOrderNo(o.getOrderNo());
        d.setCityCode(o.getCityCode());
        d.setProductCode(o.getProductCode());
        d.setOriginLat(o.getOriginLat());
        d.setOriginLng(o.getOriginLng());
        return d;
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
        cancelOtherPendingAssignsForDriver(driverId, orderNo, now);
        log.info("order accepted orderNo={} driverId={}", orderNo, driverId);
    }

    /**
     * 司机确认接其中一单后，将其余 {@link #STATUS_ASSIGNED} / {@link #STATUS_PENDING_DRIVER_CONFIRM} 单系统取消（多笔待确认互斥）。
     */
    private void cancelOtherPendingAssignsForDriver(Long driverId, String acceptedOrderNo, LocalDateTime now) {
        List<TripOrder> others = tripOrderEntityMapper.selectList(Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getDriverId, driverId)
                .eq(TripOrder::getIsDeleted, 0)
                .ne(TripOrder::getOrderNo, acceptedOrderNo)
                .in(TripOrder::getStatus, STATUS_ASSIGNED, STATUS_PENDING_DRIVER_CONFIRM));
        if (others == null || others.isEmpty()) {
            return;
        }
        for (TripOrder o : others) {
            cancelOrderSystem(o.getOrderNo(), o.getStatus(), now, "司机已接其他订单");
        }
    }

    private void cancelOrderSystem(String orderNo, Integer fromStatus, LocalDateTime now, String reason) {
        int updated = tripOrderEntityMapper.update(null,
                Wrappers.<TripOrder>lambdaUpdate()
                        .set(TripOrder::getStatus, STATUS_CANCELLED)
                        .set(TripOrder::getCancelBy, CANCEL_BY_SYSTEM)
                        .set(TripOrder::getCancelReason, reason)
                        .set(TripOrder::getCancelledAt, now)
                        .set(TripOrder::getUpdatedAt, now)
                        .set(TripOrder::getOfferExpiresAt, null)
                        .eq(TripOrder::getOrderNo, orderNo)
                        .eq(TripOrder::getIsDeleted, 0)
                        .in(TripOrder::getStatus, STATUS_ASSIGNED, STATUS_PENDING_DRIVER_CONFIRM));
        if (updated != 1) {
            log.warn("system cancel skipped orderNo={} (concurrent state change?)", orderNo);
            return;
        }
        TripOrder after = tripOrderEntityMapper.selectOne(Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getOrderNo, orderNo)
                .eq(TripOrder::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (after == null) {
            return;
        }
        OrderEvent event = new OrderEvent()
                .setOrderId(after.getId())
                .setOrderNo(orderNo)
                .setEventType("ORDER_CANCELLED")
                .setFromStatus(fromStatus)
                .setToStatus(STATUS_CANCELLED)
                .setOperatorType(OPERATOR_SYSTEM)
                .setOperatorId(null)
                .setReasonCode("DRIVER_ACCEPTED_OTHER")
                .setReasonDesc(reason)
                .setEventPayload("{}")
                .setOccurredAt(now)
                .setCreatedAt(now);
        orderEventEntityMapper.insert(event);
        log.info("order cancelled by system orderNo={} reason={}", orderNo, reason);
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

