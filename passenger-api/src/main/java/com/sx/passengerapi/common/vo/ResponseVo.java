package com.sx.passengerapi.common.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sx.passengerapi.common.enums.ExceptionCode;

import java.io.Serializable;

/**
 * 统一 API 包装；需保留无参构造器，供 Feign/Jackson 反序列化 {@code ResponseVo<T>} 时使用。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResponseVo<T> implements Serializable {
    private Integer code;
    private String msg;
    private T data;

    /** Jackson / Feign 反序列化需要 */
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

