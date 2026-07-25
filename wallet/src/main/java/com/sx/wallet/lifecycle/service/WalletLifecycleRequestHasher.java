package com.sx.wallet.lifecycle.service;

import com.sx.wallet.lifecycle.model.WalletLifecycleCommand;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class WalletLifecycleRequestHasher {
    public String hashCommand(WalletLifecycleCommand c) {
        return hash(c.operationNo().trim(), c.stepCode().trim(), Long.toString(c.customerId()),
                Long.toString(c.lifecycleVersion()), c.targetLifecycleStatus().trim(),
                c.sourceEventId().trim(), c.requestedAt().toString());
    }

    public String hashProjection(WalletLifecycleCommand c) {
        return hash(Long.toString(c.customerId()), "0", c.targetLifecycleStatus().trim(),
                Long.toString(c.lifecycleVersion()), blank(c.operationNo()),
                c.sourceEventId().trim());
    }

    private static String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder payload = new StringBuilder();
            for (String value : values) {
                String safe = value == null ? "" : value;
                byte[] bytes = safe.getBytes(StandardCharsets.UTF_8);
                payload.append(bytes.length).append(':').append(safe).append(';');
            }
            return HexFormat.of().formatHex(digest.digest(
                    payload.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256不可用", ex);
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
