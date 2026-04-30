package com.sx.capacity.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.capacity.client.order.OrderServiceResponseVo;
import com.sx.capacity.client.order.dto.AssignOrderFeignBody;
import com.sx.capacity.client.order.dto.OpenDriverOfferFeignBody;
import com.sx.capacity.model.dto.NearestDriverResult;
import com.sx.capacity.service.DriverPassengerMatchBlockService;
import com.sx.capacity.service.NearestDriverQueryService;
import com.sx.capacity.service.ProcessedEventService;
import com.sx.capacity.client.order.OrderServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class DispatchRequestedConsumer {

    private static final String EVENT_TYPE = "ORDER_CREATED_NEED_DISPATCH";

    private final ObjectMapper objectMapper;
    private final ProcessedEventService processedEventService;
    private final NearestDriverQueryService nearestDriverQueryService;
    private final OrderServiceClient orderServiceClient;
    private final DriverPassengerMatchBlockService matchBlockService;

    @Value("${capacity.dispatch.kafka.consumer-group:capacity.order.dispatch.requested.v1}")
    private String consumerGroup;

    @Value("${capacity.dispatch.driver-offer-seconds:30}")
    private int driverOfferSeconds;

    @Value("${capacity.dispatch.candidate-limit:3}")
    private int candidateLimit;

    public DispatchRequestedConsumer(ObjectMapper objectMapper,
                                    ProcessedEventService processedEventService,
                                    NearestDriverQueryService nearestDriverQueryService,
                                    OrderServiceClient orderServiceClient,
                                    DriverPassengerMatchBlockService matchBlockService) {
        this.objectMapper = objectMapper;
        this.processedEventService = processedEventService;
        this.nearestDriverQueryService = nearestDriverQueryService;
        this.orderServiceClient = orderServiceClient;
        this.matchBlockService = matchBlockService;
    }

    @KafkaListener(topics = "order.dispatch.requested.v1", groupId = "${spring.kafka.consumer.group-id}")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String value = record == null ? null : record.value();
        DispatchRequestedMessage msg;
        try {
            msg = objectMapper.readValue(value, DispatchRequestedMessage.class);
        } catch (Exception e) {
            log.error("dispatch message malformed, commit and skip. key={} err={}", record == null ? null : record.key(), e.toString());
            ack.acknowledge();
            return;
        }

        if (msg == null
                || msg.getSchemaVersion() == null || msg.getSchemaVersion() != 1
                || msg.getEventId() == null || msg.getEventId().isBlank()
                || msg.getOrderNo() == null || msg.getOrderNo().isBlank()
                || msg.getCityCode() == null || msg.getCityCode().isBlank()
                || msg.getProductCode() == null || msg.getProductCode().isBlank()
                || msg.getOrigin() == null
                || msg.getOrigin().getLat() == null
                || msg.getOrigin().getLng() == null
                || msg.getEventType() == null
                || !EVENT_TYPE.equals(msg.getEventType())) {
            log.error("dispatch message contract invalid, commit and skip. key={} eventId={}", record == null ? null : record.key(), msg == null ? null : msg.getEventId());
            ack.acknowledge();
            return;
        }

        // 幂等占坑：同一 eventId 只处理一次
        if (!processedEventService.tryMarkProcessed(consumerGroup, msg.getEventId())) {
            ack.acknowledge();
            return;
        }

        List<NearestDriverResult> candidates = nearestDriverQueryService.findNearestEligibleDrivers(
                msg.getCityCode(),
                msg.getProductCode(),
                msg.getOrigin().getLat(),
                msg.getOrigin().getLng(),
                Math.max(1, candidateLimit));

        if (candidates == null || candidates.isEmpty()) {
            log.info("dispatch no driver, end. orderNo={} eventId={}", msg.getOrderNo(), msg.getEventId());
            ack.acknowledge();
            return;
        }

        for (NearestDriverResult nr : candidates) {
            if (nr == null || nr.getDriverId() == null) {
                continue;
            }
            if (matchBlockService.isBlocked(nr.getDriverId(), msg.getPassengerId())) {
                log.info("dispatch blocked by driver-passenger pair orderNo={} driverId={} passengerId={}",
                        msg.getOrderNo(), nr.getDriverId(), msg.getPassengerId());
                continue;
            }
            try {
                if (tryAssignAndOpenOffer(msg.getOrderNo(), nr)) {
                    log.info("dispatch success. orderNo={} driverId={}", msg.getOrderNo(), nr.getDriverId());
                    ack.acknowledge();
                    return;
                }
            } catch (Exception ex) {
                // 司机服务中/状态冲突等：由下游语义决定是否继续或结束；MVP：继续下一个候选
                log.debug("dispatch attempt failed, try next. orderNo={} driverId={} err={}",
                        msg.getOrderNo(), nr.getDriverId(), ex.toString());
            }
        }

        log.info("dispatch all candidates failed, end. orderNo={} eventId={}", msg.getOrderNo(), msg.getEventId());
        ack.acknowledge();
    }

    private boolean tryAssignAndOpenOffer(String orderNo, NearestDriverResult nr) {
        AssignOrderFeignBody assign = new AssignOrderFeignBody();
        assign.setDriverId(nr.getDriverId());
        assign.setCarId(nr.getCarId());
        assign.setCompanyId(nr.getCompanyId());
        assign.setEtaSeconds(null);
        OrderServiceResponseVo<Void> a = orderServiceClient.assign(orderNo, assign);
        if (a == null || a.getCode() == null) {
            throw new IllegalStateException("assign null");
        }
        if (a.getCode() != 200) {
            // 409（司机服务中/冲突）留给上层换候选；其它错误也先视为失败
            throw new IllegalStateException(a.getMsg() == null ? "assign failed" : a.getMsg());
        }

        OpenDriverOfferFeignBody offer = new OpenDriverOfferFeignBody();
        offer.setOfferSeconds(driverOfferSeconds);
        OrderServiceResponseVo<Void> o = orderServiceClient.openDriverOffer(orderNo, offer);
        if (o == null || o.getCode() == null || o.getCode() != 200) {
            throw new IllegalStateException(o == null ? "offer null" : (o.getMsg() == null ? "offer failed" : o.getMsg()));
        }
        return true;
    }
}

