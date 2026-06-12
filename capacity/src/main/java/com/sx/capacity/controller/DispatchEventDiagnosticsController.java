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

@RestController
@RequestMapping("/api/v1/dispatch/internal/events")
public class DispatchEventDiagnosticsController {

    private final ProcessedEventService processedEventService;

    @Value("${capacity.dispatch.kafka.consumer-group:capacity.order.dispatch.requested.v1}")
    private String defaultConsumerGroup;

    public DispatchEventDiagnosticsController(ProcessedEventService processedEventService) {
        this.processedEventService = processedEventService;
    }

    @GetMapping("/{eventId}")
    public ResponseVo<CapacityProcessedEvent> get(@PathVariable String eventId,
                                                  @RequestParam(required = false) String consumerGroup) {
        return ResultUtil.success(processedEventService.get(resolveConsumerGroup(consumerGroup), eventId));
    }

    @GetMapping("/by-order/{orderNo}")
    public ResponseVo<List<CapacityProcessedEvent>> byOrder(@PathVariable String orderNo) {
        return ResultUtil.success(processedEventService.byOrder(orderNo));
    }

    @GetMapping("/recent-failed")
    public ResponseVo<List<CapacityProcessedEvent>> recentFailed(@RequestParam(required = false, defaultValue = "50") Integer limit) {
        return ResultUtil.success(processedEventService.recentFailed(limit == null ? 50 : limit));
    }

    private String resolveConsumerGroup(String consumerGroup) {
        return consumerGroup == null || consumerGroup.isBlank() ? defaultConsumerGroup : consumerGroup.trim();
    }
}
