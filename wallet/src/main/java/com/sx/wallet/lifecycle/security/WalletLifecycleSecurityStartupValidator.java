package com.sx.wallet.lifecycle.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

@Component
public class WalletLifecycleSecurityStartupValidator implements InitializingBean {
    private static final Set<String> RELAXED = Set.of("local", "dev", "test");
    private final WalletLifecycleInternalAuthProperties properties;
    private final Environment environment;

    public WalletLifecycleSecurityStartupValidator(WalletLifecycleInternalAuthProperties properties,
                                                   Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        String[] active = environment.getActiveProfiles();
        String[] effective = active.length == 0 ? environment.getDefaultProfiles() : active;
        if (effective.length > 0 && Arrays.stream(effective).allMatch(RELAXED::contains)) return;
        String token = properties.getToken();
        if (token == null || token.isBlank() || !token.equals(token.strip())
                || token.getBytes(StandardCharsets.UTF_8).length < 32
                || token.toLowerCase(Locale.ROOT).contains("change-me")) {
            throw new IllegalStateException(
                    "ACCOUNT_LIFECYCLE_INTERNAL_TOKEN必须配置为至少32字节的非开发Token");
        }
    }
}
