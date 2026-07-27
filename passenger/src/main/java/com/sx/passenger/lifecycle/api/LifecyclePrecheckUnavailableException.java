package com.sx.passenger.lifecycle.api;

/** 任一参与方预检不可用或返回 UNKNOWN 时失败关闭。 */
public class LifecyclePrecheckUnavailableException extends RuntimeException {
    public LifecyclePrecheckUnavailableException(String message) {
        super(message);
    }

    public LifecyclePrecheckUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
