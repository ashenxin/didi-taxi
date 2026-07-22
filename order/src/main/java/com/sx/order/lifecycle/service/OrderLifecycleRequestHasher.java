package com.sx.order.lifecycle.service;

import com.sx.order.lifecycle.model.OrderLifecycleCommand;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class OrderLifecycleRequestHasher {

    public String hash(OrderLifecycleCommand command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, command.operationNo().trim());
            add(digest, command.stepCode().trim());
            add(digest, Long.toString(command.customerId()));
            add(digest, command.targetLifecycleStatus().trim());
            add(digest, Long.toString(command.lifecycleVersion()));
            add(digest, command.sourceEventId().trim());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行环境不支持SHA-256", ex);
        }
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) ';');
    }
}
