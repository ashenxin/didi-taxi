package com.sx.capacity.client.order;

import java.io.Serializable;

/**
 * order-service 响应形态（与 {@code com.sx.order.common.vo.ResponseVo} 字段对齐，供 Feign 反序列化）。
 */
public class OrderServiceResponseVo<T> implements Serializable {

    private Integer code;
    private String msg;
    private T data;

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
