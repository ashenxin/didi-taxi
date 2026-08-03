package com.sx.passengerapi.ws;

/** WS 小票校验无法取得可信认证状态，调用方应按 503 处理。 */
public class PassengerWsAuthStateUnavailableException extends RuntimeException {
    public PassengerWsAuthStateUnavailableException() {
        super("passenger auth state unavailable");
    }
}
