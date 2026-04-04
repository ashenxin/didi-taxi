package com.sx.passengerapi.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 经网关访问时 CORS 由网关处理，默认不注册；勿与网关双重下发 {@code Access-Control-Allow-Origin}。
 * 浏览器直连本服务排障时设 {@code bff.browser-cors.enabled=true}。
 */
@Configuration
@ConditionalOnProperty(name = "bff.browser-cors.enabled", havingValue = "true")
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}

