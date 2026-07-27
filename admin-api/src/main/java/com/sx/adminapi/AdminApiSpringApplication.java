package com.sx.adminapi;

import com.sx.adminapi.config.AdminApiNacosLocalConfigGuard;
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
        SpringApplication application = new SpringApplication(AdminApiSpringApplication.class);
        application.addListeners(new AdminApiNacosLocalConfigGuard());
        application.run(args);
    }
}
