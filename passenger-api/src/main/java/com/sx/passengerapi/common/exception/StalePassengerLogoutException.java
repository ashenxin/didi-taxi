package com.sx.passengerapi.common.exception;

/** core logout CAS 拒绝了过期认证代次；不得继续关闭 WS 或处理订单。 */
public class StalePassengerLogoutException extends RuntimeException {

    public StalePassengerLogoutException() {
        super("认证状态已变化，请刷新后重试");
    }
}
