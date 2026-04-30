package com.sx.passengerapi.auth;

/** @param audit 1=HTTP API；2=WebSocket 握手；缺省解析为 1 以兼容登录态旧 JWT。 */
public record ParsedPassengerJwt(long customerId, String phone, long tokenVersion, int audit) {}
