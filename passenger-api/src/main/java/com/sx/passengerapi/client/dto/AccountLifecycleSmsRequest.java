package com.sx.passengerapi.client.dto;

/** BFF 调用 passenger 发送生命周期验证码的请求。 */
public record AccountLifecycleSmsRequest(Long customerId, String phone) {
}
