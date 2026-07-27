package com.sx.wallet.config;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import java.util.Arrays;

public final class WalletNacosLocalConfigGuard
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final String LOCAL_PROFILE = "local";
    private static final String CONFIG_LOADED_MARKER = "wallet.nacos.config-loaded";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        verify(event.getEnvironment());
    }

    static void verify(Environment environment) {
        boolean localProfileActive = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(LOCAL_PROFILE::equals);
        if (!localProfileActive) {
            return;
        }

        boolean remoteConfigLoaded = environment.getProperty(
                CONFIG_LOADED_MARKER,
                Boolean.class,
                false
        );
        if (!remoteConfigLoaded) {
            throw new IllegalStateException(
                    "Required Nacos config DIDI_TAXI/wallet-service-local.yml was not loaded"
            );
        }
    }
}
