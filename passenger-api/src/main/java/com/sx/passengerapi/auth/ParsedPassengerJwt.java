package com.sx.passengerapi.auth;

public record ParsedPassengerJwt(long customerId, String phone, long tokenVersion) {}
