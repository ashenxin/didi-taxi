package com.sx.calculate.common.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 兼容 ISO-8601（{@code 2026-04-10T00:00:00}）与常见 UI 串（{@code 2026-04-10 00:00:00}）。
 * admin-api 经 {@code convertValue(..., Map)} 转发时可能带空格格式。
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
