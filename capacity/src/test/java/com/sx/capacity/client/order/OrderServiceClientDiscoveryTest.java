package com.sx.capacity.client.order;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OrderServiceClientDiscoveryTest {

    @Test
    void resolvesOrderServiceThroughDiscoveryInsteadOfFixedUrl() {
        FeignClient annotation = OrderServiceClient.class.getAnnotation(FeignClient.class);

        assertNotNull(annotation);
        assertEquals("order-service", annotation.name());
        assertEquals("", annotation.url());
    }
}
