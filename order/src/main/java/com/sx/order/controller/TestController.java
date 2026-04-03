package com.sx.order.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单服务联调辅助接口（非业务 API）。
 */
@RestController
@Slf4j
public class TestController {

    /**
     * 存活探测。
     * <p>{@code GET /test/ping}</p>
     */
    @GetMapping("/test/ping")
    public String ping() {
        log.info("order ping");
        return "order ok";
    }
}
