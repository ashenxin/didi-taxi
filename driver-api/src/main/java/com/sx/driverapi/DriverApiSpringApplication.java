package com.sx.driverapi;

import com.sx.driverapi.config.DriverApiNacosLocalConfigGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class DriverApiSpringApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(DriverApiSpringApplication.class);
        application.addListeners(new DriverApiNacosLocalConfigGuard());
        application.run(args);
    }
}
