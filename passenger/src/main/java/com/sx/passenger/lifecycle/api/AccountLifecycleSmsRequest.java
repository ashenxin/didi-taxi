package com.sx.passenger.lifecycle.api;

/** 生命周期验证码发送请求。 */
public record AccountLifecycleSmsRequest(Long customerId, String phone) {
}
