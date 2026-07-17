package com.sx.passengerapi.service;

import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PassengerWalletServiceHmacTest {

    @Test
    void calculatesStandardHmacSha256Vector() throws GeneralSecurityException {
        assertEquals(
                "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
                PassengerWalletService.hmacSha256Hex("key", "The quick brown fox jumps over the lazy dog"));
    }
}
