package com.sx.passengerapi.client.dto;

/** passenger 返回的生命周期受理结果。 */
public record AccountLifecycleSubmissionData(
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
