package com.sx.gateway;

import com.sx.gateway.config.GatewayJwtProperties;
import com.sx.gateway.config.GatewayNacosLocalConfigGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GatewayJwtProperties.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(GatewayApplication.class);
        application.addListeners(new GatewayNacosLocalConfigGuard());
        application.run(args);
    }
}
