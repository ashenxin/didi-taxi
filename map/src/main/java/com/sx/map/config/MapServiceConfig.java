package com.sx.map.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AmapProperties.class)
public class MapServiceConfig {

    /**
     * 调用高德 REST 的客户端（设置超时，避免演示时长时间挂起）。
     */
    @Bean
    public RestClient amapRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(15_000);
        return RestClient.builder().requestFactory(factory).build();
    }
}
