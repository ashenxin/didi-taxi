package com.sx.calculate;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.sx.calculate")
@MapperScan("com.sx.calculate.dao")
public class CalculateSpringApplication {

    public static void main(String[] args) {
        SpringApplication.run(CalculateSpringApplication.class, args);
    }
}
