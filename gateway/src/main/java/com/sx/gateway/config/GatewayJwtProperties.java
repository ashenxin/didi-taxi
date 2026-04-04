package com.sx.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 与 admin-api {@code admin.jwt.secret} 共用同一密钥字节串（建议环境变量 {@code JWT_SECRET}）。
 */
@ConfigurationProperties(prefix = "gateway.jwt")
public class GatewayJwtProperties {

    /** 与 admin-api 一致，建议 {@code JWT_SECRET} */
    private String secret = "";

    /**
     * 若为 {@code false}，网关不对任何路径要求 JWT（仅用于本地 Postman / H5 联调）。
     * <strong>生产环境必须为 {@code true}。</strong>
     */
    private boolean requireAuth = true;

    /**
     * 是否校验 JWT {@code aud} 与路径前缀匹配（签发侧须写入 {@code aud} 后开启）。
     */
    private boolean audienceCheckEnabled = false;

    /**
     * 访问 {@code /admin/**} 時期望的 audience（仅在 audienceCheckEnabled 时生效）。
     */
    private String audienceAdmin = "admin-bff";

    private String audienceApp = "app-bff";

    private String audienceDriver = "driver-bff";

    public boolean isRequireAuth() {
        return requireAuth;
    }

    public void setRequireAuth(boolean requireAuth) {
        this.requireAuth = requireAuth;
    }

    public boolean isAudienceCheckEnabled() {
        return audienceCheckEnabled;
    }

    public void setAudienceCheckEnabled(boolean audienceCheckEnabled) {
        this.audienceCheckEnabled = audienceCheckEnabled;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getAudienceAdmin() {
        return audienceAdmin;
    }

    public void setAudienceAdmin(String audienceAdmin) {
        this.audienceAdmin = audienceAdmin;
    }

    public String getAudienceApp() {
        return audienceApp;
    }

    public void setAudienceApp(String audienceApp) {
        this.audienceApp = audienceApp;
    }

    public String getAudienceDriver() {
        return audienceDriver;
    }

    public void setAudienceDriver(String audienceDriver) {
        this.audienceDriver = audienceDriver;
    }
}
