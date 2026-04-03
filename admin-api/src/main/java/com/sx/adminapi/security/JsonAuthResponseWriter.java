package com.sx.adminapi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.adminapi.common.enums.ExceptionCode;
import com.sx.adminapi.common.util.ResultUtil;
import com.sx.adminapi.common.vo.ResponseVo;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class JsonAuthResponseWriter {

    private JsonAuthResponseWriter() {
    }

    static void write(HttpServletResponse response, ObjectMapper objectMapper, int httpStatus, ResponseVo<?> body)
            throws IOException {
        response.setStatus(httpStatus);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    static void unauthorized(HttpServletResponse response, ObjectMapper objectMapper, String msg) throws IOException {
        write(response, objectMapper, 401, ResultUtil.error(ExceptionCode.UNAUTHORIZED.getValue(), msg));
    }

    static void forbidden(HttpServletResponse response, ObjectMapper objectMapper, String msg) throws IOException {
        write(response, objectMapper, 403, ResultUtil.error(ExceptionCode.FORBIDDEN.getValue(), msg));
    }

    static void badGateway(HttpServletResponse response, ObjectMapper objectMapper) throws IOException {
        write(response, objectMapper, 502, ResultUtil.error(ExceptionCode.BAD_GATEWAY.getValue(), "服务暂时不可用，请稍后重试"));
    }
}
