package com.sx.wallet.lifecycle.messaging;

import com.sx.wallet.lifecycle.model.WalletLifecycleCommand;

import java.time.LocalDateTime;

public record WalletLifecycleCommandMessage(
        String eventId,
        String operationNo,
        String stepCode,
        long customerId,
        long lifecycleVersion,
        String targetLifecycleStatus,
        String targetDomain,
        LocalDateTime requestedAt) {

    WalletLifecycleCommand toCommand() {
        return new WalletLifecycleCommand(operationNo, stepCode, customerId, lifecycleVersion,
                targetLifecycleStatus, eventId, requestedAt);
    }
}
