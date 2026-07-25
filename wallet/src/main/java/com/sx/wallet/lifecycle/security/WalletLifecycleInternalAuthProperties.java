package com.sx.wallet.lifecycle.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter @Setter
@ConfigurationProperties(prefix = "wallet.lifecycle.internal-auth")
public class WalletLifecycleInternalAuthProperties {
    private String token;
}
