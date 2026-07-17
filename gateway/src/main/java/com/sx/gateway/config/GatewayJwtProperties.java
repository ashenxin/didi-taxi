package com.sx.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 网关 JWT 校验配置。
 *
 * 为防止跨端 token 复用，按路径前缀使用不同的签名密钥：
 * {@code /admin/**}、{@code /app/**}、{@code /driver/**} 分别对应三套 secret。
 *
 * 兼容字段 {@link #secret} 只用于识别旧配置；严格环境会拒绝单一密钥并要求三端独立 secret。
 */
@ConfigurationProperties(prefix = "gateway.jwt")
public class GatewayJwtProperties {

    /**
     * 兼容字段（禁止用于严格环境）：单一 secret。
     */
    private String secret = "";

    /** {@code /admin/**} 验签密钥 */
    private String secretAdmin = "";

    /** {@code /app/**} 验签密钥 */
    private String secretApp = "";

    /** {@code /driver/**} 验签密钥 */
    private String secretDriver = "";

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

    public String getSecretAdmin() {
        return secretAdmin;
    }

    public void setSecretAdmin(String secretAdmin) {
        this.secretAdmin = secretAdmin;
    }

    public String getSecretApp() {
        return secretApp;
    }

    public void setSecretApp(String secretApp) {
        this.secretApp = secretApp;
    }

    public String getSecretDriver() {
        return secretDriver;
    }

    public void setSecretDriver(String secretDriver) {
        this.secretDriver = secretDriver;
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
