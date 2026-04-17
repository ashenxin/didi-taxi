package com.sx.capacity;

import com.sx.capacity.config.CapacityDispatchProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.sx.capacity")
@MapperScan("com.sx.capacity.dao")
@EnableFeignClients(basePackages = "com.sx.capacity.client")
@EnableConfigurationProperties(CapacityDispatchProperties.class)
@EnableScheduling
public class CapacitySpringApplication {

    public static void main(String[] args) {
        SpringApplication.run(CapacitySpringApplication.class, args);
    }
}
