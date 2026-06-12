package com.sx.order.service;

import com.sx.order.common.exception.OrderConflictException;
import com.sx.order.common.util.OrderNoUtil;
import com.sx.order.dao.OrderIdempotentRecordMapper;
import com.sx.order.dao.OrderOutboxEventMapper;
import com.sx.order.dao.OrderEventEntityMapper;
import com.sx.order.dao.TripOrderEntityMapper;
import com.sx.order.model.OrderEvent;
import com.sx.order.model.OrderIdempotentRecord;
import com.sx.order.model.OrderOutboxEvent;
import com.sx.order.model.TripOrder;
import com.sx.order.model.dto.AssignOrderBody;
import com.sx.order.model.dto.CancelOrderBody;
import com.sx.order.model.dto.CreateOrderBody;
import com.sx.order.model.dto.FinishOrderBody;
import com.sx.order.model.dto.OpenDriverOfferBody;
import com.sx.order.model.dto.AssignedAwaitingRescheduleDto;
import com.sx.order.model.dto.PendingDispatchOrderDto;
import com.sx.order.notify.PassengerOrderChangedNotifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final String IDEMPOTENT_ACTION_CREATE_ORDER = "CREATE_ORDER";
    private static final String IDEMPOTENT_STATUS_PROCESSING = "PROCESSING";
    private static final String IDEMPOTENT_STATUS_SUCCESS = "SUCCESS";
    private static final String IDEMPOTENT_STATUS_FAILED = "FAILED";

    /**
     * 系统取消原因：待派单超时（order-service 定时任务），与 passenger 提示一致。
     */
    public static final String CANCEL_REASON_DISPATCH_TIMEOUT = "待派单超时无可用车辆，请稍后重试";

    private final TripOrderEntityMapper tripOrderEntityMapper;
    private final OrderEventEntityMapper orderEventEntityMapper;
    private final OrderOutboxEventMapper orderOutboxEventMapper;
    private final OrderIdempotentRecordMapper orderIdempotentRecordMapper;
    private final ObjectMapper objectMapper;
    private final DriverPassengerMatchBlockService matchBlockService;
    private final PassengerOrderChangedNotifier passengerOrderChangedNotifier;

    public TripOrderWriteService(TripOrderEntityMapper tripOrderEntityMapper,
                                 OrderEventEntityMapper orderEventEntityMapper,
                                 OrderOutboxEventMapper orderOutboxEventMapper,
                                 OrderIdempotentRecordMapper orderIdempotentRecordMapper,
                                 ObjectMapper objectMapper,
                                 DriverPassengerMatchBlockService matchBlockService,
                                 PassengerOrderChangedNotifier passengerOrderChangedNotifier) {
        this.tripOrderEntityMapper = tripOrderEntityMapper;
        this.orderEventEntityMapper = orderEventEntityMapper;
        this.orderOutboxEventMapper = orderOutboxEventMapper;
        this.orderIdempotentRecordMapper = orderIdempotentRecordMapper;
        this.objectMapper = objectMapper;
        this.matchBlockService = matchBlockService;
        this.passengerOrderChangedNotifier = passengerOrderChangedNotifier;
    }

    /**
     * 创建订单：落库 trip_order(status=CREATED) + 写 order_event(ORDER_CREATED)。
     * 同一乘客若存在未删除且状态非「已完单 / 已取消」的订单，则拒绝创建（{@link OrderConflictException}）。
     */
    @Transactional
    public String create(CreateOrderBody body) {
        return createInternal(body);
    }

    @Transactional
    public String create(CreateOrderBody body, String idempotencyKey) {
        String requestId = normalizeIdempotencyKey(idempotencyKey);
        String requestHash = requestHash(body);
        OrderIdempotentRecord existing = selectIdempotentRecord(requestId);
        if (existing != null) {
            return resolveExistingCreateRequest(existing, requestHash);
        }

        LocalDateTime now = LocalDateTime.now();
        OrderIdempotentRecord record = new OrderIdempotentRecord()
                .setRequestId(requestId)
                .setActionType(IDEMPOTENT_ACTION_CREATE_ORDER)
                .setPassengerId(body.getPassengerId())
                .setOrderNo(null)
                .setStatus(IDEMPOTENT_STATUS_PROCESSING)
                .setRequestHash(requestHash)
                .setResponseSnapshot(null)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        try {
            orderIdempotentRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            OrderIdempotentRecord raced = selectIdempotentRecord(requestId);
            if (raced != null) {
                return resolveExistingCreateRequest(raced, requestHash);
            }
            throw e;
        }

        try {
            String orderNo = createInternal(body);
            record.setOrderNo(orderNo)
                    .setStatus(IDEMPOTENT_STATUS_SUCCESS)
                    .setResponseSnapshot(buildCreateResponseSnapshot(orderNo))
                    .setUpdatedAt(LocalDateTime.now());
            orderIdempotentRecordMapper.updateById(record);
            return orderNo;
        } catch (RuntimeException e) {
            record.setStatus(IDEMPOTENT_STATUS_FAILED)
                    .setUpdatedAt(LocalDateTime.now());
            orderIdempotentRecordMapper.updateById(record);
            throw e;
        }
    }

    private String createInternal(CreateOrderBody body) {
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
                .setOfferRound(0)
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

        // Transactional Outbox：与订单创建同事务写入派单事件（后续由发布器投递 Kafka）
        OrderOutboxEvent outbox = new OrderOutboxEvent()
                .setTopic("order.dispatch.requested.v1")
                .setEventType("ORDER_CREATED_NEED_DISPATCH")
                .setAggregateId(orderNo)
                .setPayload("{}")
                .setStatus("PENDING")
                .setRetryCount(0)
                .setNextRetryAt(now)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        orderOutboxEventMapper.insert(outbox);
        // eventId 需要与 outbox id 对齐（JSON 中用 string 承载）
        String payload = buildDispatchRequestedPayload(body, orderNo, outbox.getId(), now);
        outbox.setPayload(payload);
        orderOutboxEventMapper.updateById(outbox);

        log.info("订单已创建 orderNo={} passengerId={} cityCode={}", orderNo, body.getPassengerId(), body.getCityCode());
        return orderNo;
    }

    private OrderIdempotentRecord selectIdempotentRecord(String requestId) {
        return orderIdempotentRecordMapper.selectOne(Wrappers.<OrderIdempotentRecord>lambdaQuery()
                .eq(OrderIdempotentRecord::getRequestId, requestId)
                .last("LIMIT 1"));
    }

    private String resolveExistingCreateRequest(OrderIdempotentRecord existing, String requestHash) {
        if (!IDEMPOTENT_ACTION_CREATE_ORDER.equals(existing.getActionType())) {
            throw new OrderConflictException("同一 Idempotency-Key 已用于其它操作");
        }
        if (!Objects.equals(existing.getRequestHash(), requestHash)) {
            throw new OrderConflictException("同一 Idempotency-Key 不能用于不同下单内容");
        }
        if (IDEMPOTENT_STATUS_SUCCESS.equals(existing.getStatus()) && StringUtils.hasText(existing.getOrderNo())) {
            log.info("下单幂等命中 requestId={} orderNo={}", existing.getRequestId(), existing.getOrderNo());
            return existing.getOrderNo();
        }
        if (IDEMPOTENT_STATUS_PROCESSING.equals(existing.getStatus())) {
            throw new OrderConflictException("请求处理中，请稍后重试");
        }
        if (IDEMPOTENT_STATUS_FAILED.equals(existing.getStatus())) {
            throw new OrderConflictException("该 Idempotency-Key 对应的下单请求已失败，请重新发起下单");
        }
        throw new OrderConflictException("该 Idempotency-Key 状态异常，请重新发起下单");
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new IllegalArgumentException("Idempotency-Key不能为空");
        }
        String key = idempotencyKey.trim();
        if (key.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key长度不能超过128");
        }
        return key;
    }

    private String requestHash(CreateOrderBody body) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("passengerId", body.getPassengerId());
            root.put("provinceCode", body.getProvinceCode());
            root.put("cityCode", body.getCityCode());
            root.put("productCode", body.getProductCode());
            root.put("origin", placeHashMap(body.getOrigin()));
            root.put("dest", placeHashMap(body.getDest()));
            root.put("estimatedAmount", decimalText(body.getEstimatedAmount()));
            root.put("fareRuleId", body.getFareRuleId());
            root.put("fareRuleSnapshot", body.getFareRuleSnapshot());
            String json = objectMapper.writeValueAsString(root);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("下单幂等请求哈希生成失败", e);
        }
    }

    private static Map<String, Object> placeHashMap(com.sx.order.model.dto.Place place) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lat", place == null ? null : decimalText(place.getLat()));
        out.put("lng", place == null ? null : decimalText(place.getLng()));
        out.put("address", place == null ? null : trimToNull(place.getAddress()));
        return out;
    }

    private static String decimalText(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private String buildCreateResponseSnapshot(String orderNo) {
        try {
            return objectMapper.writeValueAsString(Map.of("orderNo", orderNo));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("下单幂等响应快照序列化失败", e);
        }
    }

    private String buildDispatchRequestedPayload(CreateOrderBody body, String orderNo, Long outboxId, LocalDateTime now) {
        try {
            // 仅用入参组装，避免额外回查 trip_order（裁决仍以 DB 为准）
            var root = new java.util.LinkedHashMap<String, Object>();
            root.put("schemaVersion", 1);
            root.put("eventId", outboxId == null ? null : String.valueOf(outboxId));
            root.put("eventType", "ORDER_CREATED_NEED_DISPATCH");
            root.put("orderNo", orderNo);
            root.put("passengerId", body.getPassengerId());
            root.put("cityCode", body.getCityCode());
            root.put("productCode", body.getProductCode());
            var origin = new java.util.LinkedHashMap<String, Object>();
            origin.put("lat", body.getOrigin() == null ? null : body.getOrigin().getLat());
            origin.put("lng", body.getOrigin() == null ? null : body.getOrigin().getLng());
            root.put("origin", origin);
            // ISO-8601 with timezone is preferred; keep simple for MVP and use as debug only
            root.put("createdAt", now.toString());
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("outbox payload json 序列化失败", e);
        }
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
        log.info("订单已指派司机 orderNo={} driverId={}", orderNo, body.getDriverId());
    }

    /**
     * 改派：{@code ASSIGNED} 且当前无进行中的确认窗口时，更换司机/车辆；重置 {@code offer_round}，保留 {@code last_offer_at} 以便调度列表仍能识别待推进订单。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void reassign(String orderNo, AssignOrderBody body) {
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
        if (!Objects.equals(existing.getStatus(), STATUS_ASSIGNED)) {
            throw new IllegalArgumentException("订单当前状态不允许改派");
        }
        if (existing.getOfferExpiresAt() != null) {
            throw new IllegalArgumentException("确认窗口未结束，暂不可改派");
        }
        if (body.getDriverId() == null) {
            throw new IllegalArgumentException("driverId不能为空");
        }
        if (Objects.equals(existing.getDriverId(), body.getDriverId())) {
            throw new IllegalArgumentException("改派目标司机不能与当前指派相同");
        }
        assertDriverNotInServiceTrip(body.getDriverId());
        Long oldDriverId = existing.getDriverId();
        LocalDateTime now = LocalDateTime.now();
        int updated = tripOrderEntityMapper.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TripOrder>lambdaUpdate()
                        .set(TripOrder::getDriverId, body.getDriverId())
                        .set(body.getCarId() != null, TripOrder::getCarId, body.getCarId())
                        .set(body.getCompanyId() != null, TripOrder::getCompanyId, body.getCompanyId())
                        .set(TripOrder::getAssignedAt, now)
                        .set(TripOrder::getOfferRound, 0)
                        .set(TripOrder::getUpdatedAt, now)
                        .eq(TripOrder::getOrderNo, orderNo)
                        .eq(TripOrder::getIsDeleted, 0)
                        .eq(TripOrder::getStatus, STATUS_ASSIGNED)
                        .isNull(TripOrder::getOfferExpiresAt));
        if (updated != 1) {
            throw new IllegalArgumentException("改派失败，请重试");
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
                .setEventType("ORDER_REASSIGNED")
                .setFromStatus(STATUS_ASSIGNED)
                .setToStatus(STATUS_ASSIGNED)
                .setOperatorType(OPERATOR_SYSTEM)
                .setOperatorId(null)
                .setReasonCode(null)
                .setReasonDesc("运力侧改派")
                .setEventPayload("{\"oldDriverId\":" + oldDriverId + ",\"newDriverId\":" + body.getDriverId() + "}")
                .setOccurredAt(now)
                .setCreatedAt(now);
        orderEventEntityMapper.insert(event);
        log.info("订单已改派 orderNo={} oldDriverId={} newDriverId={}", orderNo, oldDriverId, body.getDriverId());
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
            log.info("取消订单跳过（已取消） orderNo={}", orderNo);
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
        log.info("乘客已取消订单 orderNo={} passengerId={}", orderNo, body.getPassengerId());
    }

    /**
     * 查询「待派单/待确认超时」候选：尚未被司机接成且创建时间早于 deadline。
     */
    public List<TripOrder> listCreatedOlderThan(LocalDateTime deadline) {
        return tripOrderEntityMapper.selectList(Wrappers.<TripOrder>lambdaQuery()
                .in(TripOrder::getStatus, STATUS_CREATED, STATUS_ASSIGNED, STATUS_PENDING_DRIVER_CONFIRM)
                .eq(TripOrder::getIsDeleted, 0)
                .lt(TripOrder::getCreatedAt, deadline)
                .orderByAsc(TripOrder::getCreatedAt)
                .last("LIMIT 200"));
    }

    /**
     * 将单笔等待派单/确认的订单系统取消为 {@code CANCELLED}；独立事务，供定时任务逐单调用。
     *
     * @return 是否本次成功更新一行
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean cancelCreatedDispatchTimeoutOne(String orderNo, LocalDateTime now) {
        if (orderNo == null || orderNo.isBlank()) {
            return false;
        }
        TripOrder before = tripOrderEntityMapper.selectOne(Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getOrderNo, orderNo)
                .eq(TripOrder::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (before == null
                || before.getStatus() == null
                || (before.getStatus() != STATUS_CREATED
                && before.getStatus() != STATUS_ASSIGNED
                && before.getStatus() != STATUS_PENDING_DRIVER_CONFIRM)) {
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
                        .in(TripOrder::getStatus, STATUS_CREATED, STATUS_ASSIGNED, STATUS_PENDING_DRIVER_CONFIRM));
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
                .setFromStatus(before.getStatus())
                .setToStatus(STATUS_CANCELLED)
                .setOperatorType(OPERATOR_SYSTEM)
                .setOperatorId(null)
                .setReasonCode("DISPATCH_TIMEOUT")
                .setReasonDesc(CANCEL_REASON_DISPATCH_TIMEOUT)
                .setEventPayload("{}")
                .setOccurredAt(now)
                .setCreatedAt(now);
        orderEventEntityMapper.insert(event);
        passengerOrderChangedNotifier.notifyAfterCommit(after.getPassengerId(), orderNo, "待派单超时系统取消");
        log.info("系统已取消订单（待派单超时） orderNo={}", orderNo);
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
                .orderByDesc(TripOrder::getAssignedAt))
                .stream()
                .filter(o -> !matchBlockService.isBlocked(driverId, o.getPassengerId()))
                .toList();
    }

    /**
     * 司机已接单但尚未到达的订单，用于司机登出时自动释单改派。
     */
    public List<TripOrder> listAcceptedBeforeArriveToDriver(Long driverId) {
        if (driverId == null) {
            throw new IllegalArgumentException("driverId不能为空");
        }
        return tripOrderEntityMapper.selectList(Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getDriverId, driverId)
                .eq(TripOrder::getStatus, STATUS_ACCEPTED)
                .eq(TripOrder::getIsDeleted, 0)
                .orderByDesc(TripOrder::getAcceptedAt));
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

    /**
     * 待派单队列（全城市）：{@code CREATED} 且起点坐标已落库，按创建时间升序；供运力服务定时迟滞匹配，无需按 cityCode 轮询。
     */
    public List<PendingDispatchOrderDto> listCreatedForDispatchAll(int limit) {
        int lim = (limit <= 0 || limit > 200) ? 100 : limit;
        List<TripOrder> list = tripOrderEntityMapper.selectList(Wrappers.<TripOrder>lambdaQuery()
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

    /**
     * 历史兼容待调度队列：{@code ASSIGNED}、无进行中的确认窗口、曾发起过 offer，按 {@code updated_at} 升序。
     */
    public List<AssignedAwaitingRescheduleDto> listAssignedAwaitingReschedule(int limit) {
        int lim = (limit <= 0 || limit > 200) ? 50 : limit;
        List<TripOrder> list = tripOrderEntityMapper.selectList(Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getStatus, STATUS_ASSIGNED)
                .eq(TripOrder::getIsDeleted, 0)
                .isNull(TripOrder::getOfferExpiresAt)
                .isNotNull(TripOrder::getLastOfferAt)
                .isNotNull(TripOrder::getDriverId)
                .orderByAsc(TripOrder::getUpdatedAt)
                .last("LIMIT " + lim));
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return list.stream().map(this::toAssignedAwaitingRescheduleDto).toList();
    }

    private AssignedAwaitingRescheduleDto toAssignedAwaitingRescheduleDto(TripOrder o) {
        AssignedAwaitingRescheduleDto d = new AssignedAwaitingRescheduleDto();
        d.setOrderNo(o.getOrderNo());
        d.setCityCode(o.getCityCode());
        d.setProductCode(o.getProductCode());
        d.setOriginLat(o.getOriginLat());
        d.setOriginLng(o.getOriginLng());
        d.setDriverId(o.getDriverId());
        d.setOfferRound(o.getOfferRound());
        return d;
    }

    private PendingDispatchOrderDto toPendingDispatchDto(TripOrder o) {
        PendingDispatchOrderDto d = new PendingDispatchOrderDto();
        d.setOrderNo(o.getOrderNo());
        d.setPassengerId(o.getPassengerId());
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
                .setReasonDesc("派单确认窗口时长 " + seconds + " 秒")
                .setEventPayload("{\"offerExpiresAt\":\"" + expires + "\",\"offerRound\":" + nextRound + "}")
                .setOccurredAt(now)
                .setCreatedAt(now);
        orderEventEntityMapper.insert(event);
        log.info("司机确认窗口已打开 orderNo={} expiresAt={} round={}", orderNo, expires, nextRound);
    }

    /**
     * 调度扫描：确认窗口超时 → 释放本轮指派并回到 {@code CREATED}，进入重新派单。
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
                log.warn("确认窗口超时处理跳过 orderNo={} reason={}", o.getOrderNo(), ex.toString());
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
                        .set(TripOrder::getStatus, STATUS_CREATED)
                        .set(TripOrder::getDriverId, null)
                        .set(TripOrder::getCarId, null)
                        .set(TripOrder::getCompanyId, null)
                        .set(TripOrder::getAssignedAt, null)
                        .set(TripOrder::getOfferExpiresAt, null)
                        .set(TripOrder::getOfferRound, 0)
                        .set(TripOrder::getLastOfferAt, null)
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
                .setToStatus(STATUS_CREATED)
                .setOperatorType(OPERATOR_SYSTEM)
                .setOperatorId(null)
                .setReasonCode("OFFER_TIMEOUT")
                .setReasonDesc("司机确认窗口超时，释放本轮指派并重新派单")
                .setEventPayload("{\"driverId\":" + existing.getDriverId() + "}")
                .setOccurredAt(now)
                .setCreatedAt(now);
        orderEventEntityMapper.insert(event);
        enqueueDispatchRequestedOutbox(after, now);
        passengerOrderChangedNotifier.notifyAfterCommit(after.getPassengerId(), orderNo, "司机确认窗口超时释放指派");
        log.info("司机确认窗口已超时并释放指派 orderNo={} driverId={}", orderNo, existing.getDriverId());
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
            log.info("接单幂等（已接单） orderNo={} driverId={}", orderNo, driverId);
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
        log.info("司机已接单 orderNo={} driverId={}", orderNo, driverId);
    }

    /**
     * 司机拒单：{@code ASSIGNED / PENDING_DRIVER_CONFIRM → CREATED}，清空指派并再次投递派单 Outbox（与下单时事件类型一致，供 capacity 消费）。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void rejectByDriver(String orderNo, Long driverId, String reasonCode) {
        String code = normalizeReasonCode(reasonCode);
        TripOrder existing = loadActiveOrder(orderNo);
        assertDriver(existing, driverId);
        Integer st = existing.getStatus();
        if (!Objects.equals(st, STATUS_ASSIGNED) && !Objects.equals(st, STATUS_PENDING_DRIVER_CONFIRM)) {
            throw new IllegalArgumentException("订单当前状态不允许拒单");
        }
        int fromStatus = st;
        LocalDateTime now = LocalDateTime.now();
        int updated = tripOrderEntityMapper.update(null,
                Wrappers.<TripOrder>lambdaUpdate()
                        .set(TripOrder::getStatus, STATUS_CREATED)
                        .set(TripOrder::getDriverId, null)
                        .set(TripOrder::getCarId, null)
                        .set(TripOrder::getCompanyId, null)
                        .set(TripOrder::getAssignedAt, null)
                        .set(TripOrder::getOfferExpiresAt, null)
                        .set(TripOrder::getOfferRound, 0)
                        .set(TripOrder::getLastOfferAt, null)
                        .set(TripOrder::getAcceptedAt, null)
                        .set(TripOrder::getUpdatedAt, now)
                        .eq(TripOrder::getOrderNo, orderNo)
                        .eq(TripOrder::getIsDeleted, 0)
                        .in(TripOrder::getStatus, STATUS_ASSIGNED, STATUS_PENDING_DRIVER_CONFIRM)
                        .eq(TripOrder::getDriverId, driverId));
        if (updated != 1) {
            throw new IllegalArgumentException("拒单失败，请重试");
        }
        String payloadJson = driverReasonPayloadJson(code);
        insertDriverEventWithReason(orderNo, driverId, "ORDER_DRIVER_REJECTED",
                fromStatus, STATUS_CREATED, now, code, "司机拒单", payloadJson);
        matchBlockService.block(driverId, existing.getPassengerId());
        TripOrder after = loadActiveOrder(orderNo);
        enqueueDispatchRequestedOutbox(after, now);
        log.info("司机已拒单 orderNo={} driverId={} reasonCode={}", orderNo, driverId, code);
    }

    /**
     * 司机取消（已接单、到达前）：{@code ACCEPTED → CREATED}，清空服务方并再次投递派单 Outbox。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void driverCancelBeforeArrive(String orderNo, Long driverId, String reasonCode) {
        String code = normalizeReasonCode(reasonCode);
        TripOrder existing = loadActiveOrder(orderNo);
        assertDriver(existing, driverId);
        Integer st = existing.getStatus();
        if (!Objects.equals(st, STATUS_ACCEPTED)) {
            throw new IllegalArgumentException("订单当前状态不允许司机取消");
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = tripOrderEntityMapper.update(null,
                Wrappers.<TripOrder>lambdaUpdate()
                        .set(TripOrder::getStatus, STATUS_CREATED)
                        .set(TripOrder::getDriverId, null)
                        .set(TripOrder::getCarId, null)
                        .set(TripOrder::getCompanyId, null)
                        .set(TripOrder::getAssignedAt, null)
                        .set(TripOrder::getOfferExpiresAt, null)
                        .set(TripOrder::getOfferRound, 0)
                        .set(TripOrder::getLastOfferAt, null)
                        .set(TripOrder::getAcceptedAt, null)
                        .set(TripOrder::getUpdatedAt, now)
                        .eq(TripOrder::getOrderNo, orderNo)
                        .eq(TripOrder::getIsDeleted, 0)
                        .eq(TripOrder::getStatus, STATUS_ACCEPTED)
                        .eq(TripOrder::getDriverId, driverId));
        if (updated != 1) {
            throw new IllegalArgumentException("司机取消失败，请重试");
        }
        String payloadJson = driverReasonPayloadJson(code);
        insertDriverEventWithReason(orderNo, driverId, "ORDER_DRIVER_CANCELLED_BEFORE_ARRIVE",
                STATUS_ACCEPTED, STATUS_CREATED, now, code, "司机到达前取消", payloadJson);
        matchBlockService.block(driverId, existing.getPassengerId());
        TripOrder after = loadActiveOrder(orderNo);
        enqueueDispatchRequestedOutbox(after, now);
        log.info("司机已取消订单（到达前） orderNo={} driverId={} reasonCode={}", orderNo, driverId, code);
    }

    private static String normalizeReasonCode(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode不能为空");
        }
        return reasonCode.trim();
    }

    private String driverReasonPayloadJson(String reasonCode) {
        try {
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("reasonCode", reasonCode);
            return objectMapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /**
     * 与 {@link #create} 一致：写入待发布的派单请求（新 eventId，避免 Kafka 幂等键冲突）。
     */
    private void enqueueDispatchRequestedOutbox(TripOrder orderRow, LocalDateTime now) {
        OrderOutboxEvent outbox = new OrderOutboxEvent()
                .setTopic("order.dispatch.requested.v1")
                .setEventType("ORDER_CREATED_NEED_DISPATCH")
                .setAggregateId(orderRow.getOrderNo())
                .setPayload("{}")
                .setStatus("PENDING")
                .setRetryCount(0)
                .setNextRetryAt(now)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        orderOutboxEventMapper.insert(outbox);
        String payload = buildDispatchRequestedPayloadFromTripOrder(orderRow, outbox.getId(), now);
        outbox.setPayload(payload);
        orderOutboxEventMapper.updateById(outbox);
    }

    private String buildDispatchRequestedPayloadFromTripOrder(TripOrder o, Long outboxId, LocalDateTime now) {
        try {
            var root = new java.util.LinkedHashMap<String, Object>();
            root.put("schemaVersion", 1);
            root.put("eventId", outboxId == null ? null : String.valueOf(outboxId));
            root.put("eventType", "ORDER_CREATED_NEED_DISPATCH");
            root.put("orderNo", o.getOrderNo());
            root.put("passengerId", o.getPassengerId());
            root.put("cityCode", o.getCityCode());
            root.put("productCode", o.getProductCode());
            var origin = new java.util.LinkedHashMap<String, Object>();
            origin.put("lat", o.getOriginLat() == null ? null : o.getOriginLat().doubleValue());
            origin.put("lng", o.getOriginLng() == null ? null : o.getOriginLng().doubleValue());
            root.put("origin", origin);
            root.put("createdAt", now.toString());
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("outbox payload json 序列化失败", e);
        }
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
            log.warn("系统取消跳过 orderNo={}（可能并发状态变更）", orderNo);
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
        log.info("系统已取消订单 orderNo={} reason={}", orderNo, reason);
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
            log.info("到达幂等 orderNo={} driverId={}", orderNo, driverId);
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
        log.info("司机已到达上车点 orderNo={} driverId={}", orderNo, driverId);
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
            log.info("开始行程幂等 orderNo={} driverId={}", orderNo, driverId);
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
        log.info("行程已开始 orderNo={} driverId={}", orderNo, driverId);
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
            log.info("完单幂等 orderNo={} driverId={}", orderNo, driverId);
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
        log.info("订单已完单 orderNo={} driverId={} finalAmount={}", orderNo, driverId, finalAmount);
    }

    private void insertDriverEvent(String orderNo, Long driverId, String eventType,
                                   Integer fromStatus, Integer toStatus, LocalDateTime now) {
        insertDriverEventWithReason(orderNo, driverId, eventType, fromStatus, toStatus, now,
                null, null, "{}");
    }

    private void insertDriverEventWithReason(String orderNo, Long driverId, String eventType,
                                             Integer fromStatus, Integer toStatus, LocalDateTime now,
                                             String reasonCode, String reasonDesc, String eventPayload) {
        TripOrder after = tripOrderEntityMapper.selectOne(Wrappers.<TripOrder>lambdaQuery()
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
                .setReasonCode(reasonCode)
                .setReasonDesc(reasonDesc)
                .setEventPayload(eventPayload == null || eventPayload.isBlank() ? "{}" : eventPayload)
                .setOccurredAt(now)
                .setCreatedAt(now);
        orderEventEntityMapper.insert(event);
    }
}
