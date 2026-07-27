package com.sx.passenger.lifecycle.api;

/** 换号或注销受理后返回给 BFF 的稳定结果。 */
public record AccountLifecycleSubmissionView(
        String operationNo,
        String operationType,
        String status,
        long customerId,
        long lifecycleVersion,
        long authEpoch,
        boolean completed,
        boolean requireLogin,
        String maskedPhone) {
}
