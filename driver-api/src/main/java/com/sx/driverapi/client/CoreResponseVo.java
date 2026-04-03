package com.sx.driverapi.client;

import java.io.Serializable;

/**
 * 与下游 order/capacity 等业务 {@code ResponseVo} JSON 字段对齐，供 Feign 反序列化。
 */
public class CoreResponseVo<T> implements Serializable {
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
