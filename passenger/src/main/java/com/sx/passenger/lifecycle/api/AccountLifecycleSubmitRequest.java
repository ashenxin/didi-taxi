package com.sx.passenger.lifecycle.api;

/**
 * passenger-api 提交生命周期操作时使用的内部应用请求。
 *
 * @param expectedLifecycleVersion 新 API 必须传入；旧 settings 灰度转调可暂时为空并由
 *                                 passenger 在消费 OTP 前读取当前版本
 */
public record AccountLifecycleSubmitRequest(
        Long customerId,
        Long expectedLifecycleVersion,
        String phone,
        String code,
        Boolean confirm) {
}
