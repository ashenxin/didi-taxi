package com.sx.passenger.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 乘客服务联调辅助接口（非业务 API）。
 */
@RestController
@Slf4j
public class TestController {

    /**
     * 存活探测。
     * {@code GET /test/ping}
     */
    @GetMapping("/test/ping")
    public String ping() {
        log.info("passenger ping");
        return "passenger ok";
    }
}
