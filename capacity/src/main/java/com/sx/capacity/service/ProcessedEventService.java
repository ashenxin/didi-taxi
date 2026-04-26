package com.sx.capacity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.capacity.dao.CapacityProcessedEventMapper;
import com.sx.capacity.model.CapacityProcessedEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ProcessedEventService {
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
}

