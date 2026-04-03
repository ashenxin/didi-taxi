package com.sx.map.common.vo;

import com.sx.map.common.enums.ExceptionCode;

import java.io.Serializable;

public class ResponseVo<T> implements Serializable {
    private Integer code;
    private String msg;
    private T data;

    public ResponseVo(Integer code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> ResponseVo<T> success(T data) {
        return new ResponseVo<>(ExceptionCode.SUCCESS.getValue(), ExceptionCode.SUCCESS.getName(), data);
    }

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

