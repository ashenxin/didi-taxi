package com.sx.passengerapi.auth;

/** 已验签且完成严格 claim 校验的乘客 JWT 载荷。 */
public record ParsedPassengerJwt(
        long customerId,
        String phone,
        long authEpoch,
        PassengerSessionScope scope,
        int audit,
        String operationNo) {
}
