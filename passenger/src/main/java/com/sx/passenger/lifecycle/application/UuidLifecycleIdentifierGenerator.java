package com.sx.passenger.lifecycle.application;

import java.util.UUID;

public final class UuidLifecycleIdentifierGenerator implements LifecycleIdentifierGenerator {
    @Override
    public String nextOperationNo() {
        return "ALO" + compactUuid();
    }

    @Override
    public String nextEventId() {
        return "EVT" + compactUuid();
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
