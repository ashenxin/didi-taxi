package com.sx.adminapi;

import com.sx.adminapi.config.AdminJwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
@EnableConfigurationProperties(AdminJwtProperties.class)
public class AdminApiSpringApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApiSpringApplication.class, args);
    }
}
