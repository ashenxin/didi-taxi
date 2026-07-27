package com.sx.calculate;

import com.sx.calculate.config.CalculateNacosLocalConfigGuard;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.sx.calculate")
@MapperScan({"com.sx.calculate.dao", "com.sx.calculate.lifecycle.dao"})
public class CalculateSpringApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(CalculateSpringApplication.class);
        application.addListeners(new CalculateNacosLocalConfigGuard());
        application.run(args);
    }
}
