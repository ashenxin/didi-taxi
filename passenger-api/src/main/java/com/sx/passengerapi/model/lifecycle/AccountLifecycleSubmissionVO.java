package com.sx.passengerapi.model.lifecycle;

/** 面向乘客返回的生命周期受理结果；注销时同时返回受限会话。 */
public record AccountLifecycleSubmissionVO(
        String operationNo,
        String operationType,
        String status,
        long lifecycleVersion,
        boolean completed,
        boolean requireLogin,
        String maskedPhone,
        String accessToken,
        String tokenType,
        Long expiresIn,
        String scope) {
}
