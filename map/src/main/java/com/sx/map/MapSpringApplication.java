package com.sx.map;

import com.sx.map.config.MapNacosLocalConfigGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MapSpringApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(MapSpringApplication.class);
        application.addListeners(new MapNacosLocalConfigGuard());
        application.run(args);
    }
}
