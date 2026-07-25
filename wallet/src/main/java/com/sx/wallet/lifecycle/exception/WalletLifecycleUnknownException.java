package com.sx.wallet.lifecycle.exception;

public class WalletLifecycleUnknownException extends RuntimeException {
    public WalletLifecycleUnknownException(String message) { super(message); }
    public WalletLifecycleUnknownException(String message, Throwable cause) { super(message, cause); }
}
