package com.sx.wallet;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.sx.wallet")
@MapperScan({"com.sx.wallet.dao", "com.sx.wallet.lifecycle.dao"})
public class WalletSpringApplication {
    public static void main(String[] args) {
        SpringApplication.run(WalletSpringApplication.class, args);
    }
}
