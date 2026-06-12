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
    private static final String RESULT_PROCESSING = "PROCESSING";
    private static final String RESULT_SUCCESS = "SUCCESS";
    private static final String RESULT_NO_DRIVER = "NO_DRIVER";
    private static final String RESULT_FAILED = "FAILED";
    private static final String RESULT_INVALID = "INVALID";
    private static final String RESULT_MALFORMED = "MALFORMED";

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
            String diagnosticEventId = diagnosticEventId(record);
            processedEventService.recordDiagnostic(consumerGroup, diagnosticEventId, RESULT_MALFORMED, null, null, e.toString());
            log.error("dispatch message malformed, commit and skip. eventId={} key={} err={}",
                    diagnosticEventId, record == null ? null : record.key(), e.toString());
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
            String eventId = msg == null || msg.getEventId() == null || msg.getEventId().isBlank()
                    ? diagnosticEventId(record)
                    : msg.getEventId();
            String orderNo = msg == null ? null : msg.getOrderNo();
            processedEventService.recordDiagnostic(consumerGroup, eventId, RESULT_INVALID, orderNo, null, "dispatch message contract invalid");
            log.error("dispatch message contract invalid, commit and skip. key={} eventId={} orderNo={}",
                    record == null ? null : record.key(), eventId, orderNo);
            ack.acknowledge();
            return;
        }

        // 幂等占坑：同一 eventId 只处理一次
        if (!processedEventService.tryMarkProcessed(consumerGroup, msg.getEventId())) {
            log.info("dispatch duplicate event skipped. eventId={} orderNo={}", msg.getEventId(), msg.getOrderNo());
            ack.acknowledge();
            return;
        }
        processedEventService.recordResult(consumerGroup, msg.getEventId(), RESULT_PROCESSING, msg.getOrderNo(), null, null);

        List<NearestDriverResult> candidates = nearestDriverQueryService.findNearestEligibleDrivers(
                msg.getCityCode(),
                msg.getProductCode(),
                msg.getOrigin().getLat(),
                msg.getOrigin().getLng(),
                Math.max(1, candidateLimit));

        if (candidates == null || candidates.isEmpty()) {
            processedEventService.recordResult(consumerGroup, msg.getEventId(), RESULT_NO_DRIVER, msg.getOrderNo(), null, "no eligible driver");
            log.info("dispatch no driver, end. orderNo={} eventId={} cityCode={} productCode={}",
                    msg.getOrderNo(), msg.getEventId(), msg.getCityCode(), msg.getProductCode());
            ack.acknowledge();
            return;
        }

        Long lastDriverId = null;
        String lastFailure = null;
        for (NearestDriverResult nr : candidates) {
            if (nr == null || nr.getDriverId() == null) {
                continue;
            }
            lastDriverId = nr.getDriverId();
            if (matchBlockService.isBlocked(nr.getDriverId(), msg.getPassengerId())) {
                lastFailure = "driver-passenger pair blocked";
                log.info("dispatch blocked by driver-passenger pair orderNo={} driverId={} passengerId={}",
                        msg.getOrderNo(), nr.getDriverId(), msg.getPassengerId());
                continue;
            }
            try {
                if (tryAssignAndOpenOffer(msg.getOrderNo(), nr)) {
                    processedEventService.recordResult(consumerGroup, msg.getEventId(), RESULT_SUCCESS, msg.getOrderNo(), nr.getDriverId(), null);
                    log.info("dispatch success. orderNo={} eventId={} driverId={}",
                            msg.getOrderNo(), msg.getEventId(), nr.getDriverId());
                    ack.acknowledge();
                    return;
                }
            } catch (Exception ex) {
                // 司机服务中/状态冲突等：由下游语义决定是否继续或结束；MVP：继续下一个候选
                lastFailure = ex.toString();
                log.debug("dispatch attempt failed, try next. orderNo={} driverId={} err={}",
                        msg.getOrderNo(), nr.getDriverId(), ex.toString());
            }
        }

        processedEventService.recordResult(consumerGroup, msg.getEventId(), RESULT_FAILED, msg.getOrderNo(), lastDriverId,
                lastFailure == null ? "all candidates failed" : lastFailure);
        log.info("dispatch all candidates failed, end. orderNo={} eventId={} lastDriverId={} reason={}",
                msg.getOrderNo(), msg.getEventId(), lastDriverId, lastFailure);
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

    private static String diagnosticEventId(ConsumerRecord<String, String> record) {
        if (record == null) {
            return "kafka:unknown";
        }
        return record.topic() + ":" + record.partition() + ":" + record.offset();
    }
}
