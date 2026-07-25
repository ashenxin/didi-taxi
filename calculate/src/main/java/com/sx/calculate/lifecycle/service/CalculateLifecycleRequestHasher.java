package com.sx.calculate.lifecycle.service;

import com.sx.calculate.lifecycle.model.ApplyCalculateLifecycleProjectionCommand;
import com.sx.calculate.lifecycle.model.CalculateLifecycleCommand;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class CalculateLifecycleRequestHasher {

    public String hashProjection(ApplyCalculateLifecycleProjectionCommand command) {
        return hash(Long.toString(command.customerId()),
                Integer.toString(command.businessStatus()),
                command.lifecycleStatus().trim(),
                Long.toString(command.lifecycleVersion()),
                blankToNull(command.operationNo()),
                command.sourceEventId().trim());
    }

    public String hashCommand(CalculateLifecycleCommand command) {
        return hash(command.operationNo().trim(), command.stepCode().trim(),
                Long.toString(command.customerId()),
                Long.toString(command.lifecycleVersion()),
                command.targetLifecycleStatus().trim(), command.sourceEventId().trim());
    }

    public String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) ';');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行环境不支持SHA-256", ex);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
