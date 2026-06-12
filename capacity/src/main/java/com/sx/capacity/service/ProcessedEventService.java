package com.sx.capacity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.capacity.dao.CapacityProcessedEventMapper;
import com.sx.capacity.model.CapacityProcessedEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ProcessedEventService {
    private static final int ERROR_MESSAGE_MAX_LENGTH = 1000;

    private final CapacityProcessedEventMapper mapper;

    public ProcessedEventService(CapacityProcessedEventMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 抢占式幂等占坑：插入成功返回 true；唯一键冲突表示已处理过，返回 false。
     */
    public boolean tryMarkProcessed(String consumerGroup, String eventId) {
        if (consumerGroup == null || consumerGroup.isBlank() || eventId == null || eventId.isBlank()) {
            return false;
        }
        CapacityProcessedEvent row = new CapacityProcessedEvent()
                .setConsumerGroup(consumerGroup)
                .setEventId(eventId)
                .setProcessedAt(LocalDateTime.now());
        try {
            mapper.insert(row);
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    public void recordResult(String consumerGroup,
                             String eventId,
                             String resultStatus,
                             String orderNo,
                             Long driverId,
                             String errorMessage) {
        if (consumerGroup == null || consumerGroup.isBlank() || eventId == null || eventId.isBlank()) {
            return;
        }
        mapper.update(null, Wrappers.<CapacityProcessedEvent>lambdaUpdate()
                .set(CapacityProcessedEvent::getResultStatus, blankToNull(resultStatus))
                .set(CapacityProcessedEvent::getOrderNo, blankToNull(orderNo))
                .set(CapacityProcessedEvent::getDriverId, driverId)
                .set(CapacityProcessedEvent::getErrorMessage, truncate(errorMessage))
                .eq(CapacityProcessedEvent::getConsumerGroup, consumerGroup)
                .eq(CapacityProcessedEvent::getEventId, eventId));
    }

    public void recordDiagnostic(String consumerGroup,
                                 String eventId,
                                 String resultStatus,
                                 String orderNo,
                                 Long driverId,
                                 String errorMessage) {
        if (consumerGroup == null || consumerGroup.isBlank() || eventId == null || eventId.isBlank()) {
            return;
        }
        CapacityProcessedEvent row = new CapacityProcessedEvent()
                .setConsumerGroup(consumerGroup)
                .setEventId(eventId)
                .setProcessedAt(LocalDateTime.now())
                .setResultStatus(blankToNull(resultStatus))
                .setOrderNo(blankToNull(orderNo))
                .setDriverId(driverId)
                .setErrorMessage(truncate(errorMessage));
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException ex) {
            mapper.update(null, Wrappers.<CapacityProcessedEvent>lambdaUpdate()
                    .set(CapacityProcessedEvent::getResultStatus, blankToNull(resultStatus))
                    .set(CapacityProcessedEvent::getOrderNo, blankToNull(orderNo))
                    .set(CapacityProcessedEvent::getDriverId, driverId)
                    .set(CapacityProcessedEvent::getErrorMessage, truncate(errorMessage))
                    .eq(CapacityProcessedEvent::getConsumerGroup, consumerGroup)
                    .eq(CapacityProcessedEvent::getEventId, eventId)
                    .and(w -> w.isNull(CapacityProcessedEvent::getResultStatus)
                            .or()
                            .in(CapacityProcessedEvent::getResultStatus, "PROCESSING", "INVALID", "MALFORMED")));
        }
    }

    public CapacityProcessedEvent get(String consumerGroup, String eventId) {
        if (consumerGroup == null || consumerGroup.isBlank() || eventId == null || eventId.isBlank()) {
            return null;
        }
        return mapper.selectOne(Wrappers.<CapacityProcessedEvent>lambdaQuery()
                .eq(CapacityProcessedEvent::getConsumerGroup, consumerGroup)
                .eq(CapacityProcessedEvent::getEventId, eventId)
                .last("LIMIT 1"));
    }

    public List<CapacityProcessedEvent> byOrder(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("orderNo不能为空");
        }
        return mapper.selectList(Wrappers.<CapacityProcessedEvent>lambdaQuery()
                .eq(CapacityProcessedEvent::getOrderNo, orderNo.trim())
                .orderByDesc(CapacityProcessedEvent::getProcessedAt)
                .orderByDesc(CapacityProcessedEvent::getId));
    }

    public List<CapacityProcessedEvent> recentFailed(int limit) {
        int lim = limit <= 0 || limit > 200 ? 50 : limit;
        return mapper.selectList(Wrappers.<CapacityProcessedEvent>lambdaQuery()
                .in(CapacityProcessedEvent::getResultStatus, "FAILED", "INVALID", "MALFORMED")
                .orderByDesc(CapacityProcessedEvent::getProcessedAt)
                .last("LIMIT " + lim));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= ERROR_MESSAGE_MAX_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }
}
