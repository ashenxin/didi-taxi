package com.sx.driverapi.config;

import feign.RequestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Configuration
public class FeignForwardHeadersConfig {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Bean
    public RequestInterceptor forwardAuthHeaders() {
        return template -> {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (!(attrs instanceof ServletRequestAttributes sra)) {
                return;
            }
            HttpServletRequest req = sra.getRequest();
            String userId = req.getHeader(USER_ID_HEADER);
            if (userId != null && !userId.isBlank()) {
                template.header(USER_ID_HEADER, userId);
            }
            String requestId = req.getHeader(REQUEST_ID_HEADER);
            if (requestId != null && !requestId.isBlank()) {
                template.header(REQUEST_ID_HEADER, requestId);
            }
        };
    }
}

