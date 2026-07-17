package com.sx.order.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.order.service.TripOrderSettlementService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SettlementRequestedListenerTest {

    @Test
    void delegatesOrderNumberFromOutboxPayload() {
        TripOrderSettlementService service = mock(TripOrderSettlementService.class);
        SettlementRequestedListener listener = new SettlementRequestedListener(new ObjectMapper(), service);

        listener.onMessage("{\"schemaVersion\":\"1.0\",\"eventId\":\"42\","
                + "\"orderNo\":\"T202607170010\"}");

        verify(service).process("T202607170010");
    }
}
