package com.sx.driverapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class DriverApiSpringApplication {

    public static void main(String[] args) {
        SpringApplication.run(DriverApiSpringApplication.class, args);
    }
}

