package com.sx.driverapi.auth;

/**
 * driver-api 签发的 JWT 载荷（子集）。
 */
public record ParsedDriverJwt(long driverId, String phone, long tokenVersion, int audit) {
}
