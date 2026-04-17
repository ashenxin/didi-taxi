package com.sx.adminapi.common.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 兼容 ISO-8601 与 {@code yyyy-MM-dd HH:mm:ss}；避免仅用 {@code JsonFormat} 时序列化也变成空格格式，
 * 导致 Feign 转发 calculate 时对方无法解析。
 */
public class FlexibleLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

    private static final DateTimeFormatter SPACE_SEP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String text = p.getValueAsString();
        if (text == null || text.isBlank()) {
            return null;
        }
        text = text.trim();
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(text, SPACE_SEP);
        } catch (DateTimeParseException e) {
            return (LocalDateTime) ctxt.handleWeirdStringValue(LocalDateTime.class, text,
                    "expected ISO-8601 or yyyy-MM-dd HH:mm:ss");
        }
    }
}
