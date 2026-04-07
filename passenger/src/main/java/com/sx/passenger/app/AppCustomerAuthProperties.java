package com.sx.passenger.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 乘客端短信验证码与发送频控（Redis）；生产对接真实短信网关时在发送逻辑中替换 mock 分支。
 */
@ConfigurationProperties(prefix = "passenger.app.customer-auth.sms")
public class AppCustomerAuthProperties {

    /** 验证码 TTL（秒） */
    private int codeTtlSeconds = 300;

    /** 同一手机号两次发送最小间隔（秒） */
    private int minIntervalSeconds = 60;

    /** 自然日每手机号最大发送次数 */
    private int dailyLimitPerPhone = 5;

    /** 自然日每手机号最大登录失败次数（密码+验证码合并计算） */
    private int dailyLoginFailLimitPerPhone = 5;

    /**
     * 为 true 时不调用外网短信，仅在日志打印验证码（本地/测试）；生产务必 false 并接入短信商。
     */
    private boolean mockSendEnabled = true;

    public int getCodeTtlSeconds() {
        return codeTtlSeconds;
    }

    public void setCodeTtlSeconds(int codeTtlSeconds) {
        this.codeTtlSeconds = codeTtlSeconds;
    }

    public int getMinIntervalSeconds() {
        return minIntervalSeconds;
    }

    public void setMinIntervalSeconds(int minIntervalSeconds) {
        this.minIntervalSeconds = minIntervalSeconds;
    }

    public int getDailyLimitPerPhone() {
        return dailyLimitPerPhone;
    }

    public void setDailyLimitPerPhone(int dailyLimitPerPhone) {
        this.dailyLimitPerPhone = dailyLimitPerPhone;
    }

    public int getDailyLoginFailLimitPerPhone() {
        return dailyLoginFailLimitPerPhone;
    }

    public void setDailyLoginFailLimitPerPhone(int dailyLoginFailLimitPerPhone) {
        this.dailyLoginFailLimitPerPhone = dailyLoginFailLimitPerPhone;
    }

    public boolean isMockSendEnabled() {
        return mockSendEnabled;
    }

    public void setMockSendEnabled(boolean mockSendEnabled) {
        this.mockSendEnabled = mockSendEnabled;
    }
}
