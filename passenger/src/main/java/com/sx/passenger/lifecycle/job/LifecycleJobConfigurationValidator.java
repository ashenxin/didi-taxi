package com.sx.passenger.lifecycle.job;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * 生命周期 XXL-JOB 与消息配置的生产启动校验器。
 *
 * <p>非 local/dev/test 环境拒绝弱 Token、首尾空格和 loopback 服务地址；
 * XXL-JOB 开启但消息发布关闭也会直接启动失败。
 */
@Component
public class LifecycleJobConfigurationValidator implements InitializingBean {
    private static final Set<String> RELAXED_PROFILES = Set.of("local", "dev", "test");

    private final Environment environment;

    public LifecycleJobConfigurationValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        boolean xxlEnabled =
                environment.getProperty("xxl.job.enabled", Boolean.class, false);
        if (!xxlEnabled) return;

        boolean messagingEnabled = environment.getProperty(
                "passenger.account-lifecycle.messaging.enabled", Boolean.class, false);
        if (!messagingEnabled) {
            throw new IllegalStateException(
                    "xxl.job.enabled=true时必须开启生命周期Kafka消息");
        }
        if (hasOnlyExplicitRelaxedProfiles()) return;
        validateProductionToken(environment.getProperty("xxl.job.access-token"));
        validateProductionEndpoint("XXL_JOB_ADMIN_ADDRESSES",
                environment.getProperty("xxl.job.admin.addresses"));
        validateProductionEndpoint("XXL_JOB_PASSENGER_EXECUTOR_ADDRESS",
                environment.getProperty("xxl.job.executor.address"));
    }

    /** 校验生产 Token 的非空、长度和非默认值约束。 */
    static void validateProductionToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("XXL_JOB_ACCESS_TOKEN must be configured");
        }
        if (!token.equals(token.strip())) {
            throw new IllegalStateException(
                    "XXL_JOB_ACCESS_TOKEN must not contain whitespace at boundaries");
        }
        if (token.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "XXL_JOB_ACCESS_TOKEN must contain at least 32 bytes");
        }
        String normalized = token.toLowerCase(Locale.ROOT);
        if ("default_token".equals(normalized)
                || normalized.startsWith("dev-")
                || normalized.contains("change-me")) {
            throw new IllegalStateException(
                    "XXL_JOB_ACCESS_TOKEN must not use a development default");
        }
    }

    /** 校验生产端点非空、格式正确且不指向本机回环地址。 */
    static void validateProductionEndpoint(String name, String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException(name + " must be configured");
        }
        if (!endpoint.equals(endpoint.strip())) {
            throw new IllegalStateException(
                    name + " must not contain whitespace at boundaries");
        }
        String normalized = endpoint.toLowerCase(Locale.ROOT);
        if (normalized.contains("://127.0.0.1")
                || normalized.contains("://localhost")
                || normalized.contains("://[::1]")) {
            throw new IllegalStateException(
                    name + " must use a production-routable address");
        }
    }

    private boolean hasOnlyExplicitRelaxedProfiles() {
        String[] active = environment.getActiveProfiles();
        return active.length > 0
                && Arrays.stream(active).allMatch(RELAXED_PROFILES::contains);
    }
}
