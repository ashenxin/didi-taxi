package com.sx.passenger.lifecycle.application.cancel;

public record AccountCancellationFenceResult(long operationId,
                                             String operationNo,
                                             long customerId,
                                             long appliedLifecycleVersion,
                                             long restrictedAuthEpoch,
                                             String status) {
}
