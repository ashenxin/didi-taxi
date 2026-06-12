package com.sx.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.order.dao.OrderOutboxEventMapper;
import com.sx.order.dao.TripOrderEntityMapper;
import com.sx.order.model.OrderOutboxEvent;
import com.sx.order.model.TripOrder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OutboxDiagnosticsService {

    private static final int STATUS_CREATED = 0;
    private static final int STATUS_ASSIGNED = 1;
    private static final int STATUS_ACCEPTED = 2;
    private static final int STATUS_ARRIVED = 3;
    private static final int STATUS_STARTED = 4;
    private static final int STATUS_FINISHED = 5;
    private static final int STATUS_CANCELLED = 6;
    private static final int STATUS_PENDING_DRIVER_CONFIRM = 7;

    private final OrderOutboxEventMapper outboxMapper;
    private final TripOrderEntityMapper tripOrderMapper;

    public OutboxDiagnosticsService(OrderOutboxEventMapper outboxMapper, TripOrderEntityMapper tripOrderMapper) {
        this.outboxMapper = outboxMapper;
        this.tripOrderMapper = tripOrderMapper;
    }

    public Map<String, Object> summary() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pendingCount", countByStatus("PENDING"));
        out.put("processingCount", countByStatus("PROCESSING"));
        out.put("publishedCount", countByStatus("PUBLISHED"));
        out.put("failedCount", countByStatus("FAILED"));
        out.put("oldestPendingAgeSeconds", oldestAgeSeconds("PENDING", now));
        out.put("oldestProcessingAgeSeconds", oldestAgeSeconds("PROCESSING", now));
        out.put("recentFailed", failed(5));
        return out;
    }

    public List<OrderOutboxEvent> byOrder(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("orderNo不能为空");
        }
        return outboxMapper.selectList(Wrappers.<OrderOutboxEvent>lambdaQuery()
                .eq(OrderOutboxEvent::getAggregateId, orderNo.trim())
                .orderByDesc(OrderOutboxEvent::getId));
    }

    public List<OrderOutboxEvent> failed(int limit) {
        int lim = limit <= 0 || limit > 200 ? 50 : limit;
        return outboxMapper.selectList(Wrappers.<OrderOutboxEvent>lambdaQuery()
                .eq(OrderOutboxEvent::getStatus, "FAILED")
                .orderByDesc(OrderOutboxEvent::getUpdatedAt)
                .last("LIMIT " + lim));
    }

    public OrderOutboxEvent retry(Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("id不能为空");
        }
        OrderOutboxEvent row = outboxMapper.selectById(id);
        if (row == null) {
            throw new IllegalArgumentException("outbox事件不存在");
        }
        if (!"FAILED".equals(row.getStatus()) && !"PROCESSING".equals(row.getStatus())) {
            throw new IllegalArgumentException("仅FAILED或PROCESSING状态可手动重试");
        }
        int updated = outboxMapper.update(null, Wrappers.<OrderOutboxEvent>lambdaUpdate()
                .set(OrderOutboxEvent::getStatus, "PENDING")
                .set(OrderOutboxEvent::getNextRetryAt, LocalDateTime.now())
                .set(OrderOutboxEvent::getProcessingAt, null)
                .set(OrderOutboxEvent::getProcessingBy, null)
                .set(OrderOutboxEvent::getUpdatedAt, LocalDateTime.now())
                .eq(OrderOutboxEvent::getId, id)
                .in(OrderOutboxEvent::getStatus, "FAILED", "PROCESSING"));
        if (updated != 1) {
            throw new IllegalArgumentException("outbox事件状态已变化，请刷新后重试");
        }
        return outboxMapper.selectById(id);
    }

    public Map<String, Object> dispatchTrace(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("orderNo不能为空");
        }
        TripOrder order = tripOrderMapper.selectOne(Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getOrderNo, orderNo.trim())
                .eq(TripOrder::getIsDeleted, 0)
                .last("LIMIT 1"));
        List<OrderOutboxEvent> outboxes = byOrder(orderNo);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderNo", orderNo.trim());
        out.put("orderStatus", order == null ? null : order.getStatus());
        out.put("orderStatusText", order == null ? null : statusText(order.getStatus()));
        out.put("outbox", outboxes);
        out.put("suggestion", suggestion(order, outboxes));
        return out;
    }

    private Long countByStatus(String status) {
        return outboxMapper.selectCount(Wrappers.<OrderOutboxEvent>lambdaQuery()
                .eq(OrderOutboxEvent::getStatus, status));
    }

    private Long oldestAgeSeconds(String status, LocalDateTime now) {
        OrderOutboxEvent row = outboxMapper.selectOne(Wrappers.<OrderOutboxEvent>lambdaQuery()
                .eq(OrderOutboxEvent::getStatus, status)
                .orderByAsc(OrderOutboxEvent::getCreatedAt)
                .last("LIMIT 1"));
        if (row == null || row.getCreatedAt() == null) {
            return null;
        }
        return Math.max(0, Duration.between(row.getCreatedAt(), now).getSeconds());
    }

    private static String suggestion(TripOrder order, List<OrderOutboxEvent> outboxes) {
        if (order == null) {
            return "订单不存在，请先确认orderNo是否正确";
        }
        if (outboxes == null || outboxes.isEmpty()) {
            return "未找到outbox事件，检查创建/改派事务是否写入order_outbox_event";
        }
        boolean hasFailed = outboxes.stream().anyMatch(o -> "FAILED".equals(o.getStatus()));
        if (hasFailed) {
            return "存在FAILED outbox事件，检查lastError与Kafka连接，必要时调用retry接口";
        }
        boolean hasPending = outboxes.stream().anyMatch(o -> "PENDING".equals(o.getStatus()));
        if (hasPending) {
            return "存在PENDING outbox事件，检查XXL orderOutboxPublish是否启用或nextRetryAt是否未到";
        }
        boolean hasProcessing = outboxes.stream().anyMatch(o -> "PROCESSING".equals(o.getStatus()));
        if (hasProcessing) {
            return "存在PROCESSING outbox事件，若长时间不变可等待回收或手动retry";
        }
        boolean hasPublished = outboxes.stream().anyMatch(o -> "PUBLISHED".equals(o.getStatus()));
        if (hasPublished && Integer.valueOf(STATUS_CREATED).equals(order.getStatus())) {
            return "outbox已发布但订单仍为CREATED（待派单/重新派单），继续检查capacity消费结果、司机池与Kafka lag";
        }
        return "未发现明显outbox阻塞，请结合订单事件与capacity消费诊断继续排查";
    }

    private static String statusText(Integer status) {
        if (status == null) return "UNKNOWN";
        return switch (status) {
            case STATUS_CREATED -> "CREATED（待派单/重新派单）";
            case STATUS_ASSIGNED -> "ASSIGNED（已指派/待接单）";
            case STATUS_ACCEPTED -> "ACCEPTED（司机已接单）";
            case STATUS_ARRIVED -> "ARRIVED（司机已到达）";
            case STATUS_STARTED -> "STARTED（行程中）";
            case STATUS_FINISHED -> "FINISHED（已完成）";
            case STATUS_CANCELLED -> "CANCELLED（已取消）";
            case STATUS_PENDING_DRIVER_CONFIRM -> "PENDING_DRIVER_CONFIRM（待司机确认）";
            default -> "UNKNOWN";
        };
    }
}
