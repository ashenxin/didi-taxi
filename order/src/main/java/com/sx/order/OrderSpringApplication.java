package com.sx.order;

import com.sx.order.config.OrderNacosLocalConfigGuard;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.sx.order")
@MapperScan({"com.sx.order.dao", "com.sx.order.lifecycle.dao"})
public class OrderSpringApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(OrderSpringApplication.class);
        application.addListeners(new OrderNacosLocalConfigGuard());
        application.run(args);
    }
}
