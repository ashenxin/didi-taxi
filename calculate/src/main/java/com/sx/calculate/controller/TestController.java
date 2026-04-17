package com.sx.calculate.controller;

import com.sx.calculate.common.util.ResultUtil;
import com.sx.calculate.common.vo.ResponseVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计价服务联调辅助接口（非业务 API）。
 */
@RestController
@Slf4j
public class TestController {

    /**
     * 存活探测。
     * {@code GET /test/ping}
     */
    @GetMapping("/test/ping")
    public ResponseVo<String> ping() {
        log.info("计价服务连通检查");
        return ResultUtil.success("calculate ok");
    }
}
