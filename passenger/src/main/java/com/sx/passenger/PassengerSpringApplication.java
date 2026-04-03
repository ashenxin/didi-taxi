package com.sx.passenger;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.sx.passenger")
@MapperScan("com.sx.passenger.dao")
public class PassengerSpringApplication {

    public static void main(String[] args) {
        SpringApplication.run(PassengerSpringApplication.class, args);
    }
}
