package com.sx.passenger.lifecycle.orchestration;

import java.time.LocalDateTime;

/** 同步 HTTP 参与者契约；路由字段留在 Passenger 内部，不泄漏给目标服务。 */
record LifecycleParticipantHttpCommand(
        String operationNo,
        String stepCode,
        long customerId,
        long lifecycleVersion,
        String targetLifecycleStatus,
        String sourceEventId,
        LocalDateTime requestedAt) {

    static LifecycleParticipantHttpCommand from(LifecycleParticipantCommand command) {
        return new LifecycleParticipantHttpCommand(
                command.operationNo(), command.stepCode(), command.customerId(),
                command.lifecycleVersion(), command.targetLifecycleStatus(),
                command.eventId(), command.requestedAt());
    }
}
