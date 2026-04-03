package com.sx.adminapi.common.util;

import com.sx.adminapi.common.enums.ExceptionCode;
import com.sx.adminapi.common.exception.BizErrorException;
import com.sx.adminapi.common.vo.ResponseVo;

public class ResultUtil {

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

    /**
     * 基础异常
     *
     * @param errorCode 错误码
     * @param msg       消息
     * @param <T>       泛型
     * @return ResponseVo
     */
    public static <T> ResponseVo<T> error(Integer errorCode, String msg) {
        return new ResponseVo<>(errorCode, msg, null);
    }

    /**
     * 业务异常
     *
     * @param e   业务异常
     * @param <T> 泛型
     * @return ResponseVo
     */
    public static <T> ResponseVo<T> bizError(BizErrorException e) {
        return error(e.getErrorCode(), e.getErrorMessage());
    }

    /**
     * 请求参数错误
     *
     * @param msg 消息
     * @param <T> 泛型
     * @return ResponseVo
     */
    public static <T> ResponseVo<T> requestError(String msg) {
        return error(ExceptionCode.BAD_REQUEST.getValue(), msg);
    }
}
