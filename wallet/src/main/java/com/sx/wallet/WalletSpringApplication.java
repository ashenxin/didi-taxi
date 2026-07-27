package com.sx.wallet;

import com.sx.wallet.config.WalletNacosLocalConfigGuard;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.sx.wallet")
@MapperScan({"com.sx.wallet.dao", "com.sx.wallet.lifecycle.dao"})
public class WalletSpringApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(WalletSpringApplication.class);
        application.addListeners(new WalletNacosLocalConfigGuard());
        application.run(args);
    }
}
