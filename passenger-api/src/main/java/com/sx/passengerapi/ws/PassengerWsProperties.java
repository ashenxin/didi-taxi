package com.sx.passengerapi.ws;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 乘客 WebSocket 开关与超时（与文档《乘客端与司机端_WebSocket_对比》§0 对齐）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "passenger.ws")
public class PassengerWsProperties {

    /** 运维总闸：false 时不签发 ws-token、握手应拒绝（由拦截器实现）。 */
    private boolean enabled = true;

    /** 无 PING 则关闭连接（毫秒），默认 90s。 */
    private long heartbeatTimeoutMs = 90_000L;
}
