package com.sx.wallet.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class MockPaymentStartupValidator implements InitializingBean {
    private static final Set<String> BUILT_IN_SAFE_PROFILES = Set.of("local", "dev", "test");
    private final MockPaymentProperties properties;
    private final Environment environment;

    public MockPaymentStartupValidator(MockPaymentProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (environment == null) {
            return;
        }
        String[] active = environment.getActiveProfiles();
        validate(active.length == 0
                ? Arrays.asList(environment.getDefaultProfiles())
                : Arrays.asList(active));
    }

    public void validate(Collection<String> profiles) {
        if (!properties.isEnabled()) {
            return;
        }
        List<String> configured = properties.getAllowedProfiles() == null
                ? List.of()
                : properties.getAllowedProfiles().stream()
                .map(profile -> profile == null ? "" : profile.toLowerCase(Locale.ROOT))
                .toList();
        boolean valid = profiles != null && !profiles.isEmpty()
                && profiles.stream()
                .map(profile -> profile == null ? "" : profile.toLowerCase(Locale.ROOT))
                .allMatch(profile -> BUILT_IN_SAFE_PROFILES.contains(profile)
                        && configured.contains(profile));
        if (!valid) {
            throw new IllegalStateException("当前环境禁止启用mock支付 profiles=" + profiles);
        }
    }
}
