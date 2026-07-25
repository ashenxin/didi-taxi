package com.sx.wallet.common.vo;

public class ResponseVo<T> {
    private int code;
    private String msg;
    private T data;
    private String error;

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
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

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
