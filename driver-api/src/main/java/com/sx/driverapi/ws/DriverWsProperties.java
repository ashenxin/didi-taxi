package com.sx.driverapi.ws;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "driver.ws")
@Getter
@Setter
public class DriverWsProperties {
    /**
     * 司机端心跳超时阈值：超过该时长未收到 ping，则判离线并清会话。
     */
    private long heartbeatTimeoutMs = 45_000;

    /**
     * 服务端对已连接司机轮询 assigned 并推送的间隔（过渡期替代前端轮询）。
     */
    private long assignedPollIntervalMs = 2_000;
}

