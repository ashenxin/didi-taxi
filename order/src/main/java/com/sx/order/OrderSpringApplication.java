package com.sx.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.sx.order")
@MapperScan("com.sx.order.dao")
@EnableScheduling
public class OrderSpringApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderSpringApplication.class, args);
    }
}

