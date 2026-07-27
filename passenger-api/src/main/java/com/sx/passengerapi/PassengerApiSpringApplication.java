package com.sx.passengerapi;

import com.sx.passengerapi.config.PassengerApiNacosLocalConfigGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class PassengerApiSpringApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(PassengerApiSpringApplication.class);
        application.addListeners(new PassengerApiNacosLocalConfigGuard());
        application.run(args);
    }
}
