package com.sx.wallet.lifecycle.model;

public record WalletLifecyclePrecheckRequest(long customerId) {
    public WalletLifecyclePrecheckRequest {
        if (customerId <= 0) throw new IllegalArgumentException("customerId必须为正数");
    }
}
