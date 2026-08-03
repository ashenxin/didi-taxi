package com.sx.passengerapi.ws;

/** 有效的受限会话不能建立普通业务 WebSocket。 */
public class PassengerWsRestrictedException extends RuntimeException {
    public PassengerWsRestrictedException() {
        super("restricted passenger session cannot use websocket");
    }
}
