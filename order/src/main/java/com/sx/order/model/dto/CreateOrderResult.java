package com.sx.order.model.dto;

/**
 * 创建订单返回值。
 */
public class CreateOrderResult {
    private String orderNo;

    public CreateOrderResult() {
    }

    public CreateOrderResult(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }
}

