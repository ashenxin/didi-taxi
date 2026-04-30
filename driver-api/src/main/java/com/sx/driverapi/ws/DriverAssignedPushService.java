package com.sx.driverapi.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.driverapi.service.DriverBffService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 将「指派单列表」推送给当前司机 WebSocket；供定时任务、心跳与订单变更接口共用。
 */
@Service
@Slf4j
public class DriverAssignedPushService {

    private final DriverWsSessionRegistry registry;
    private final DriverBffService driverBffService;
    private final ObjectMapper objectMapper;

    public DriverAssignedPushService(DriverWsSessionRegistry registry,
                                     DriverBffService driverBffService,
                                     ObjectMapper objectMapper) {
        this.registry = registry;
        this.driverBffService = driverBffService;
        this.objectMapper = objectMapper;
    }

    /**
     * @param force true 时忽略与上次列表的 hash 比对（用于拒单/接单等变更后立即刷新）
     */
    public void pushAssignedIfChanged(long driverId, boolean force) {
        var ds = registry.get(driverId);
        if (ds == null) {
            return;
        }
        List<?> rows;
        try {
            rows = driverBffService.listAssigned(driverId);
        } catch (Exception e) {
            log.warn("push assigned failed driverId={} err={}", driverId, e.toString());
            Map<String, Object> msg = Map.of(
                    "type", "ASSIGNED_ERROR",
                    "ts", System.currentTimeMillis(),
                    "driverId", driverId,
                    "data", Map.of("message", String.valueOf(e.getMessage() == null ? e.toString() : e.getMessage()))
            );
            try {
                registry.safeSendText(ds.getSession(), objectMapper.writeValueAsString(msg));
            } catch (Exception ignore) {
                // ignore
            }
            return;
        }
        String hash = hashAssigned(rows);
        if (!force && hash.equals(ds.getLastAssignedHash())) {
            return;
        }
        ds.setLastAssignedHash(hash);
        Map<String, Object> msg = Map.of(
                "type", "ASSIGNED_LIST",
                "ts", System.currentTimeMillis(),
                "driverId", driverId,
                "data", Map.of("list", rows)
        );
        try {
            registry.safeSendText(ds.getSession(), objectMapper.writeValueAsString(msg));
        } catch (Exception e) {
            log.debug("ws json serialize failed driverId={} err={}", driverId, e.toString());
        }
    }

    private static String hashAssigned(List<?> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        return list.stream()
                .sorted(Comparator.comparing(o -> {
                    try {
                        var m = o.getClass().getMethod("getOrderNo");
                        Object v = m.invoke(o);
                        return v == null ? "" : v.toString();
                    } catch (Exception e) {
                        return o == null ? "" : o.toString();
                    }
                }))
                .map(o -> {
                    if (o == null) {
                        return "null";
                    }
                    try {
                        var m1 = o.getClass().getMethod("getOrderNo");
                        var m2 = o.getClass().getMethod("getStatus");
                        Object orderNo = m1.invoke(o);
                        Object status = m2.invoke(o);
                        return (orderNo == null ? "" : orderNo.toString()) + "#" + (status == null ? "" : status.toString());
                    } catch (Exception e) {
                        return o.toString();
                    }
                })
                .collect(Collectors.joining("|"));
    }
}
