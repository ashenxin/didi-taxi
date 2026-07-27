package com.sx.passenger;

import com.sx.passenger.config.PassengerNacosLocalConfigGuard;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.sx.passenger")
@MapperScan({"com.sx.passenger.dao", "com.sx.passenger.lifecycle.persistence.mapper"})
public class PassengerSpringApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(PassengerSpringApplication.class);
        application.addListeners(new PassengerNacosLocalConfigGuard());
        application.run(args);
    }
}
