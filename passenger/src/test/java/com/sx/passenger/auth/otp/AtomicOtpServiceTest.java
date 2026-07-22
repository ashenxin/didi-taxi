package com.sx.passenger.auth.otp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AtomicOtpServiceTest {

    @Mock
    StringRedisTemplate redis;

    private AtomicOtpService service;

    @BeforeEach
    void setUp() {
        service = new AtomicOtpService(redis, new OtpKeyFactory());
    }

    @Test
    void mapsLuaReturnCodesWithoutSecondRedisCommand() {
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.<String>anyList(), eq("111111")))
                .thenReturn(0L, 1L, 2L);
        OtpSubject subject = OtpSubject.login("13800138000");

        assertThat(service.consume(OtpPurpose.LOGIN, subject, "111111")).isEqualTo(OtpConsumeResult.MISSING);
        assertThat(service.consume(OtpPurpose.LOGIN, subject, "111111")).isEqualTo(OtpConsumeResult.MISMATCH);
        assertThat(service.consume(OtpPurpose.LOGIN, subject, "111111")).isEqualTo(OtpConsumeResult.CONSUMED);

        verify(redis, never()).delete(anyString());
    }

}
