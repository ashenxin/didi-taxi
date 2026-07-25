package com.sx.wallet.lifecycle.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter @Setter
@Component
@ConfigurationProperties(prefix = "wallet.lifecycle.write-fence")
public class WalletLifecycleWriteFenceProperties {
    private String mode = "SHADOW";
}
