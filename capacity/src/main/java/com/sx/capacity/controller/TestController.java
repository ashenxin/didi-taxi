package com.sx.capacity.controller;

import com.sx.capacity.common.util.ResultUtil;
import com.sx.capacity.common.vo.ResponseVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运力服务联调辅助接口（非业务 API）。
 */
@RestController
@Slf4j
public class TestController {

    /**
     * 打日志并返回固定文案（链路/日志验证）。
     * {@code GET /test/sleuth}
     */
    @GetMapping("/test/sleuth")
    public ResponseVo<String> testSleuth() {
        log.info("info");
        log.error("error");
        log.debug("debug");
        return ResultUtil.success("Hello sleuth capacity");
    }
}
