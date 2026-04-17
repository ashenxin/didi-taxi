package com.sx.adminapi.common.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 管理端 API 对外统一为 {@code yyyy-MM-dd HH:mm:ss}，避免默认 ISO 带 {@code T} 直接透出到前端。
 */
public class LocalDateTimeDisplaySerializer extends JsonSerializer<LocalDateTime> {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
        } else {
            gen.writeString(value.format(FMT));
        }
    }
}
