package com.sx.passengerapi.client.dto;

public record InternalLogoutRequest(long customerId, long expectedAuthEpoch) {
}
