package com.sx.passenger.lifecycle.application;

import java.util.UUID;

/** 使用无短横线 UUID 生成生命周期业务标识的默认实现。 */
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
