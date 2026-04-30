package com.sx.passengerapi.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 向当前在线乘客下行轻量「请拉详情」消息；单机内存发送，多实例时另接 Redis Pub（见文档 §0.6）。
 */
@Service
@Slf4j
public class PassengerWsNotifyService {

    private final PassengerWsProperties wsProperties;
    private final PassengerWsSessionRegistry registry;
    private final ObjectMapper objectMapper;
    /** 单调序号，按 orderNo 分桶，供客户端去重。 */
    private final ConcurrentHashMap<String, AtomicLong> seqByOrderNo = new ConcurrentHashMap<>();

    public PassengerWsNotifyService(PassengerWsProperties wsProperties,
                                    PassengerWsSessionRegistry registry,
                                    ObjectMapper objectMapper) {
        this.wsProperties = wsProperties;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    /**
     * 若该乘客在本节点有 WS，则推送 {@code ORDER_CHANGED}；否则忽略。
     */
    public void notifyOrderChanged(long passengerId, String orderNo) {
        if (!wsProperties.isEnabled() || passengerId <= 0 || orderNo == null || orderNo.isBlank()) {
            return;
        }
        PassengerWsSessionRegistry.PassengerSession ps = registry.get(passengerId);
        if (ps == null || ps.getSession() == null || !ps.getSession().isOpen()) {
            return;
        }
        long seq = seqByOrderNo.computeIfAbsent(orderNo, k -> new AtomicLong()).incrementAndGet();
        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", orderNo);
        data.put("seq", seq);
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("type", "ORDER_CHANGED");
        envelope.put("ts", System.currentTimeMillis());
        envelope.put("data", data);
        try {
            String json = objectMapper.writeValueAsString(envelope);
            registry.safeSendText(ps.getSession(), json);
            log.debug("WS ORDER_CHANGED passengerId={} orderNo={} seq={}", passengerId, orderNo, seq);
        } catch (JsonProcessingException e) {
            log.warn("WS notify serialize failed passengerId={} orderNo={} err={}", passengerId, orderNo, e.toString());
        }
    }
}
