package com.sx.passengerapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class PassengerApiSpringApplication {

    public static void main(String[] args) {
        SpringApplication.run(PassengerApiSpringApplication.class, args);
    }
}

