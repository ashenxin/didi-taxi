package com.sx.capacity.app;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "capacity.app.driver-auth")
public class DriverAuthProperties {

    /**
     * OTP 有效期（秒）。
     */
    private long codeTtlSeconds = 300;

    /**
     * 同手机号最小发送间隔（秒）。
     */
    private long minIntervalSeconds = 60;

    /**
     * 同手机号自然日发送上限。
     */
    private long dailySmsLimitPerPhone = 5;

    /**
     * 同手机号自然日登录失败上限（密码 + 短信合并）。
     */
    private long dailyLoginFailLimitPerPhone = 5;

    /**
     * 是否启用 mock 短信发送（打印到日志）。
     */
    private boolean mockSendEnabled = true;
}

