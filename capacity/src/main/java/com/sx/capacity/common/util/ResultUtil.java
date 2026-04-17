package com.sx.capacity.common.util;

import com.sx.capacity.common.enums.ExceptionCode;
import com.sx.capacity.common.vo.ResponseVo;

public class ResultUtil {
    public static <T> ResponseVo<T> success(T data) {
        return ResponseVo.success(data);
    }

    public static <T> ResponseVo<T> error(Integer errorCode, String msg) {
        return new ResponseVo<>(errorCode, msg, null);
    }

    public static <T> ResponseVo<T> requestError(String msg) {
        return error(ExceptionCode.BAD_REQUEST.getValue(), msg);
    }

    public static <T> ResponseVo<T> notImplemented(String msg) {
        return error(ExceptionCode.NOT_IMPLEMENTED.getValue(), msg);
    }

    public static <T> ResponseVo<T> notFound(String msg) {
        return error(ExceptionCode.NOT_FOUND.getValue(), msg);
    }
}
