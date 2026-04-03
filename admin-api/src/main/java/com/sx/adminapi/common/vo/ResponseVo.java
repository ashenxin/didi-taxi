package com.sx.adminapi.common.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sx.adminapi.common.enums.ExceptionCode;

import java.io.Serializable;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ResponseVo<T> implements Serializable {

    private Integer code;

    private String msg;

    private T data;

    public ResponseVo() {
    }

    public ResponseVo(Integer code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public ResponseVo(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    /**
     * 请求成功返回
     *
     * @param data data
     * @param <T>  泛型
     * @return ResponseVo
     */
    public static <T> ResponseVo<T> success(T data) {
        return new ResponseVo<>(ExceptionCode.SUCCESS.getValue(), ExceptionCode.SUCCESS.getName(), data);
    }

    public static <T> ResponseVo<T> success() {
        return success(null);
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
