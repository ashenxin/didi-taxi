package com.sx.passenger.internal.auth.dto;

public record InternalLogoutRequest(long customerId, long expectedAuthEpoch) {
}
