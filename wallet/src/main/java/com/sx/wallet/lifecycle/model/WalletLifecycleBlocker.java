package com.sx.wallet.lifecycle.model;

public record WalletLifecycleBlocker(String code, String resourceType,
                                     String resourceNo, String action) {
}
