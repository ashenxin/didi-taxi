package com.sx.passenger.lifecycle.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 计算生命周期计划内容摘要。
 *
 * <p>摘要会随 Operation 一起固化，用来证明操作创建时实际采用的完整计划内容。
 * 序列化前固定属性和 Map 顺序，避免相同配置仅因字段输出顺序不同而得到不同摘要。
 */
public final class LifecyclePlanDigest {
    /** 输出稳定顺序的专用 JSON Mapper，不复用业务接口的可变序列化配置。 */
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    /**
     * 对规范化后的计划 JSON 计算小写十六进制 SHA-256。
     *
     * @param plan 原始计划定义
     * @return 长度为 64 的计划摘要
     */
    public String sha256(LifecyclePlanDefinition plan) {
        try {
            byte[] canonical = mapper.writeValueAsBytes(plan);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot calculate lifecycle plan digest", e);
        }
    }
}
