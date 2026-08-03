package com.sx.passenger.time;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/** Passenger DATETIME 字段统一使用的无时区本地时间。 */
public final class PassengerPersistenceTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private PassengerPersistenceTime() {
    }

    public static LocalDateTime fromInstant(Instant instant) {
        return LocalDateTime.ofInstant(Objects.requireNonNull(instant, "instant must not be null"), ZONE);
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }
}
