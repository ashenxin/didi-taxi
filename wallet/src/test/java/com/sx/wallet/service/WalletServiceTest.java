package com.sx.wallet.service;

import com.sx.wallet.model.dto.AutoPayRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;

class WalletServiceTest {
    private final WalletService service = new WalletService(null, true, null);

    @Test
    void rejectsNonPositiveAutoPayAmount() {
        AutoPayRequest request = request(BigDecimal.ZERO);

        assertThrows(IllegalArgumentException.class, () -> service.autoPay(request));
    }

    private static AutoPayRequest request(BigDecimal amount) {
        AutoPayRequest request = new AutoPayRequest();
        request.setOrderNo("ORDER-1");
        request.setPassengerId(10001L);
        request.setAmount(amount);
        request.setIdempotencyKey("idem-1");
        return request;
    }
}
