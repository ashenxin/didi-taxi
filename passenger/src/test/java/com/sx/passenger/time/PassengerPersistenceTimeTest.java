package com.sx.passenger.time;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class PassengerPersistenceTimeTest {
    @Test
    void convertsInstantToShanghaiDatabaseTime() {
        assertThat(PassengerPersistenceTime.fromInstant(Instant.parse("2026-08-01T14:21:19Z")))
                .isEqualTo(LocalDateTime.of(2026, 8, 1, 22, 21, 19));
    }
}
