package com.sx.passenger.lifecycle.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sx.passenger.lifecycle.application.cancel.FenceAccountCancellationCommand;
import com.sx.passenger.lifecycle.application.phone.ChangeCustomerPhoneCommand;
import com.sx.passenger.lifecycle.domain.LifecycleOperationType;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 生命周期幂等请求摘要生成器。
 *
 * <p>先递归排序 JSON 对象字段，再加入操作类型、账号版本和关键业务输入，
 * 最后计算 SHA-256。同一幂等键只有摘要相同才允许重放。
 */
@Component
public final class LifecycleRequestHasher {
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** 计算注销建栅栏请求摘要。 */
    public String hash(FenceAccountCancellationCommand command) {
        return hash(LifecycleOperationType.ACCOUNT_CANCEL, command.customerId(),
                command.expectedLifecycleVersion(), command.sanitizedRequestContextJson());
    }

    /** 计算换号请求摘要；新手机号属于请求身份的一部分。 */
    public String hash(ChangeCustomerPhoneCommand command) {
        return digest(LifecycleOperationType.PHONE_CHANGE.name() + "\n" + command.customerId() + "\n"
                + command.expectedLifecycleVersion() + "\n" + command.newPhone() + "\n"
                + canonicalJson(command.sanitizedRequestContextJson()));
    }

    public String hash(LifecycleOperationType operationType, long customerId,
                       long expectedLifecycleVersion, String sanitizedRequestContextJson) {
        if (operationType == null) {
            throw new IllegalArgumentException("operationType must not be null");
        }
        String canonical = operationType.name() + "\n" + customerId + "\n" + expectedLifecycleVersion
                + "\n" + canonicalJson(sanitizedRequestContextJson);
        return digest(canonical);
    }

    private static String digest(String canonical) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** 校验 JSON 并生成字段顺序稳定的紧凑表示。 */
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

    /** 递归排序对象字段；数组保持原顺序，因为数组顺序具有业务含义。 */
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
