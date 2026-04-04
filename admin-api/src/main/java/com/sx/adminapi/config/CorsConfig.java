package com.sx.adminapi.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 经 {@code gateway} 访问时 CORS 由网关唯一处理，此处默认关闭，避免下游与网关各写一遍 {@code Access-Control-Allow-Origin} 导致浏览器报「multiple values」。
 * 仅在不走网关、浏览器直连 BFF 排障时设 {@code bff.browser-cors.enabled=true}。
 */
@Configuration
@ConditionalOnProperty(name = "bff.browser-cors.enabled", havingValue = "true")
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/admin/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}

