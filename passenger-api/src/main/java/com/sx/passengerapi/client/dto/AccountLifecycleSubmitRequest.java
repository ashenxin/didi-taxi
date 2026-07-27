package com.sx.passengerapi.client.dto;

/** BFF 调用 passenger 生命周期应用接口的请求。 */
public record AccountLifecycleSubmitRequest(
        Long customerId,
        Long expectedLifecycleVersion,
        String phone,
        String code,
        Boolean confirm) {
}
