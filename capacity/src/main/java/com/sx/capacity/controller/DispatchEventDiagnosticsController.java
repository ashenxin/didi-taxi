package com.sx.capacity.controller;

import com.sx.capacity.common.util.ResultUtil;
import com.sx.capacity.common.vo.ResponseVo;
import com.sx.capacity.model.CapacityProcessedEvent;
import com.sx.capacity.service.ProcessedEventService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运力派单事件消费诊断接口，用于排查 Kafka 事件的幂等消费结果与失败记录。
 * 统一前缀：{@code /api/v1/dispatch/internal/events}；仅供内部运维与联调使用。
 */
@RestController
@RequestMapping("/api/v1/dispatch/internal/events")
public class DispatchEventDiagnosticsController {

    private final ProcessedEventService processedEventService;

    @Value("${capacity.dispatch.kafka.consumer-group:capacity.order.dispatch.requested.v1}")
    private String defaultConsumerGroup;

    public DispatchEventDiagnosticsController(ProcessedEventService processedEventService) {
        this.processedEventService = processedEventService;
    }

    /**
     * 按事件 ID 和消费组查询幂等消费记录；未指定消费组时使用当前派单消费者组。
     * {@code GET /api/v1/dispatch/internal/events/{eventId}?consumerGroup=}
     */
    @GetMapping("/{eventId}")
    public ResponseVo<CapacityProcessedEvent> get(@PathVariable String eventId,
                                                  @RequestParam(required = false) String consumerGroup) {
        return ResultUtil.success(processedEventService.get(resolveConsumerGroup(consumerGroup), eventId));
    }

    /**
     * 按订单号查询关联的派单事件消费记录。
     * {@code GET /api/v1/dispatch/internal/events/by-order/{orderNo}}
     */
    @GetMapping("/by-order/{orderNo}")
    public ResponseVo<List<CapacityProcessedEvent>> byOrder(@PathVariable String orderNo) {
        return ResultUtil.success(processedEventService.byOrder(orderNo));
    }

    /**
     * 查询最近失败的派单事件消费记录，默认返回 50 条。
     * {@code GET /api/v1/dispatch/internal/events/recent-failed?limit=}
     */
    @GetMapping("/recent-failed")
    public ResponseVo<List<CapacityProcessedEvent>> recentFailed(@RequestParam(required = false, defaultValue = "50") Integer limit) {
        return ResultUtil.success(processedEventService.recentFailed(limit == null ? 50 : limit));
    }

    private String resolveConsumerGroup(String consumerGroup) {
        return consumerGroup == null || consumerGroup.isBlank() ? defaultConsumerGroup : consumerGroup.trim();
    }
}
