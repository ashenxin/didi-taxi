package com.sx.order.common.exception;

/**
 * 业务冲突（如乘客已有进行中订单仍尝试创建新单）。
 */
public class OrderConflictException extends RuntimeException {

    public OrderConflictException(String message) {
        super(message);
    }
}
