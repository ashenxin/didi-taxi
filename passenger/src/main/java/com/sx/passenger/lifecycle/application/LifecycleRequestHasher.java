package com.sx.passenger.lifecycle.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sx.passenger.lifecycle.application.cancel.FenceAccountCancellationCommand;
import com.sx.passenger.lifecycle.domain.LifecycleOperationType;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

@Component
public final class LifecycleRequestHasher {
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public String hash(FenceAccountCancellationCommand command) {
        return hash(LifecycleOperationType.ACCOUNT_CANCEL, command.customerId(),
                command.expectedLifecycleVersion(), command.sanitizedRequestContextJson());
    }

    public String hash(LifecycleOperationType operationType, long customerId,
                       long expectedLifecycleVersion, String sanitizedRequestContextJson) {
        if (operationType == null) {
            throw new IllegalArgumentException("operationType must not be null");
        }
        String canonical = operationType.name() + "\n" + customerId + "\n" + expectedLifecycleVersion
                + "\n" + canonicalJson(sanitizedRequestContextJson);
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String canonicalJson(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("sanitizedRequestContextJson must not be blank");
        }
        try {
            return json.writeValueAsString(sort(json.readTree(value)));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("sanitizedRequestContextJson must be valid JSON", ex);
        }
    }

    private JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = json.createObjectNode();
            Map<String, JsonNode> fields = new TreeMap<>();
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((name, value) -> sorted.set(name, sort(value)));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = json.createArrayNode();
            node.forEach(child -> sorted.add(sort(child)));
            return sorted;
        }
        return node;
    }
}
