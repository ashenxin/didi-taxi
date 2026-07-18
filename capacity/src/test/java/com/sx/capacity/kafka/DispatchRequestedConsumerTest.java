package com.sx.capacity.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.capacity.client.order.OrderServiceClient;
import com.sx.capacity.client.order.OrderServiceResponseVo;
import com.sx.capacity.client.order.dto.AssignOrderFeignBody;
import com.sx.capacity.client.order.dto.OpenDriverOfferFeignBody;
import com.sx.capacity.model.dto.NearestDriverResult;
import com.sx.capacity.service.DriverPassengerMatchBlockService;
import com.sx.capacity.service.NearestDriverQueryService;
import com.sx.capacity.service.ProcessedEventService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DispatchRequestedConsumerTest {

    private static final String CONSUMER_GROUP = "capacity.order.dispatch.requested.v1";
    private static final String EVENT_ID = "EVENT-1";
    private static final String ORDER_NO = "ORDER-1";

    private final ProcessedEventService processedEventService = mock(ProcessedEventService.class);
    private final NearestDriverQueryService nearestDriverQueryService = mock(NearestDriverQueryService.class);
    private final OrderServiceClient orderServiceClient = mock(OrderServiceClient.class);
    private final DriverPassengerMatchBlockService matchBlockService = mock(DriverPassengerMatchBlockService.class);
    private final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    private DispatchRequestedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new DispatchRequestedConsumer(new ObjectMapper(), processedEventService,
                nearestDriverQueryService, orderServiceClient, matchBlockService);
        ReflectionTestUtils.setField(consumer, "consumerGroup", CONSUMER_GROUP);
        ReflectionTestUtils.setField(consumer, "driverOfferSeconds", 30);
        ReflectionTestUtils.setField(consumer, "candidateLimit", 3);
    }

    @Test
    void successfulDispatchAssignsCandidateAndRecordsSuccess() {
        NearestDriverResult candidate = candidate(80001L);
        when(processedEventService.tryMarkProcessed(CONSUMER_GROUP, EVENT_ID)).thenReturn(true);
        when(nearestDriverQueryService.findNearestEligibleDrivers(
                "330100", "ECONOMY", 30.25, 120.21, 3, 10001L))
                .thenReturn(List.of(candidate));
        when(matchBlockService.isBlocked(80001L, 10001L)).thenReturn(false);
        when(orderServiceClient.assign(eq(ORDER_NO), any())).thenReturn(response(200, null));
        when(orderServiceClient.openDriverOffer(eq(ORDER_NO), any())).thenReturn(response(200, null));

        consumer.onMessage(record(validMessage()), acknowledgment);

        ArgumentCaptor<AssignOrderFeignBody> assignCaptor = ArgumentCaptor.forClass(AssignOrderFeignBody.class);
        verify(orderServiceClient).assign(eq(ORDER_NO), assignCaptor.capture());
        assertThat(assignCaptor.getValue().getDriverId()).isEqualTo(80001L);
        assertThat(assignCaptor.getValue().getCarId()).isEqualTo(90001L);
        assertThat(assignCaptor.getValue().getCompanyId()).isEqualTo(70001L);

        ArgumentCaptor<OpenDriverOfferFeignBody> offerCaptor = ArgumentCaptor.forClass(OpenDriverOfferFeignBody.class);
        verify(orderServiceClient).openDriverOffer(eq(ORDER_NO), offerCaptor.capture());
        assertThat(offerCaptor.getValue().getOfferSeconds()).isEqualTo(30);
        verify(processedEventService).recordResult(
                CONSUMER_GROUP, EVENT_ID, "SUCCESS", ORDER_NO, 80001L, null);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void duplicateEventIsAcknowledgedWithoutDispatchingAgain() {
        when(processedEventService.tryMarkProcessed(CONSUMER_GROUP, EVENT_ID)).thenReturn(false);

        consumer.onMessage(record(validMessage()), acknowledgment);

        verify(acknowledgment).acknowledge();
        verify(processedEventService, never()).recordResult(
                eq(CONSUMER_GROUP), eq(EVENT_ID), any(), any(), any(), any());
        verifyNoInteractions(nearestDriverQueryService, orderServiceClient, matchBlockService);
    }

    @Test
    void assignConflictTriesNextCandidateAndRecordsFinalDriver() {
        NearestDriverResult first = candidate(80001L);
        NearestDriverResult second = candidate(80002L);
        when(processedEventService.tryMarkProcessed(CONSUMER_GROUP, EVENT_ID)).thenReturn(true);
        when(nearestDriverQueryService.findNearestEligibleDrivers(
                "330100", "ECONOMY", 30.25, 120.21, 3, 10001L))
                .thenReturn(List.of(first, second));
        when(matchBlockService.isBlocked(anyLong(), eq(10001L))).thenReturn(false);
        when(orderServiceClient.assign(eq(ORDER_NO), any()))
                .thenReturn(response(409, "司机服务中"))
                .thenReturn(response(200, null));
        when(orderServiceClient.openDriverOffer(eq(ORDER_NO), any())).thenReturn(response(200, null));

        consumer.onMessage(record(validMessage()), acknowledgment);

        ArgumentCaptor<AssignOrderFeignBody> assignCaptor = ArgumentCaptor.forClass(AssignOrderFeignBody.class);
        verify(orderServiceClient, times(2)).assign(eq(ORDER_NO), assignCaptor.capture());
        assertThat(assignCaptor.getAllValues())
                .extracting(AssignOrderFeignBody::getDriverId)
                .containsExactly(80001L, 80002L);
        verify(orderServiceClient, times(1)).openDriverOffer(eq(ORDER_NO), any());
        verify(processedEventService).recordResult(
                CONSUMER_GROUP, EVENT_ID, "SUCCESS", ORDER_NO, 80002L, null);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void noCandidateRecordsConclusionAndAcknowledgesMessage() {
        when(processedEventService.tryMarkProcessed(CONSUMER_GROUP, EVENT_ID)).thenReturn(true);
        when(nearestDriverQueryService.findNearestEligibleDrivers(
                "330100", "ECONOMY", 30.25, 120.21, 3, 10001L))
                .thenReturn(List.of());

        consumer.onMessage(record(validMessage()), acknowledgment);

        verify(processedEventService).recordResult(
                CONSUMER_GROUP, EVENT_ID, "NO_DRIVER", ORDER_NO, null, "no eligible driver");
        verifyNoInteractions(orderServiceClient, matchBlockService);
        verify(acknowledgment).acknowledge();
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("order.dispatch.requested.v1", 0, 10L, ORDER_NO, value);
    }

    private static String validMessage() {
        return """
                {
                  "schemaVersion": 1,
                  "eventId": "EVENT-1",
                  "eventType": "ORDER_CREATED_NEED_DISPATCH",
                  "orderNo": "ORDER-1",
                  "passengerId": 10001,
                  "cityCode": "330100",
                  "productCode": "ECONOMY",
                  "origin": {"lat": 30.25, "lng": 120.21},
                  "createdAt": "2026-07-18T08:00:00Z"
                }
                """;
    }

    private static NearestDriverResult candidate(Long driverId) {
        NearestDriverResult result = new NearestDriverResult();
        result.setDriverId(driverId);
        result.setCarId(driverId + 10000);
        result.setCompanyId(driverId - 10000);
        result.setCityCode("330100");
        result.setProductCode("ECONOMY");
        return result;
    }

    private static OrderServiceResponseVo<Void> response(int code, String message) {
        OrderServiceResponseVo<Void> response = new OrderServiceResponseVo<>();
        response.setCode(code);
        response.setMsg(message);
        return response;
    }
}
