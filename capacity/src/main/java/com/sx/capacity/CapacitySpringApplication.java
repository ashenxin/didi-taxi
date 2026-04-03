package com.sx.capacity;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.sx.capacity")
@MapperScan("com.sx.capacity.dao")
public class CapacitySpringApplication {

    public static void main(String[] args) {
        SpringApplication.run(CapacitySpringApplication.class, args);
    }
}
