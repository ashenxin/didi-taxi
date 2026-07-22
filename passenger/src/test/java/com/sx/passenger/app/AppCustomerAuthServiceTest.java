package com.sx.passenger.app;

import com.sx.passenger.app.dto.AppSmsLoginRequest;
import com.sx.passenger.auth.otp.AtomicOtpService;
import com.sx.passenger.auth.otp.OtpConsumeResult;
import com.sx.passenger.auth.otp.OtpPurpose;
import com.sx.passenger.auth.otp.OtpSubject;
import com.sx.passenger.common.vo.ResponseVo;
import com.sx.passenger.dao.CustomerEntityMapper;
import com.sx.passenger.model.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppCustomerAuthServiceTest {

    private final CustomerEntityMapper customerMapper = mock(CustomerEntityMapper.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final AtomicOtpService otpService = mock(AtomicOtpService.class);
    private AppCustomerAuthService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOperations);
        service = new AppCustomerAuthService(customerMapper, redis, new AppCustomerAuthProperties(), otpService);
    }

    @Test
    void sendSmsCodeStoresLoginOtp() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(valueOperations.increment(anyString())).thenReturn(1L);

        ResponseVo<?> response = service.sendSmsCode("13800138000");

        assertThat(response.getCode()).isEqualTo(200);
        verify(otpService).store(eq(OtpPurpose.LOGIN), eq(OtpSubject.login("13800138000")), anyString(),
                eq(Duration.ofSeconds(300)));
    }

    @Test
    void missingAndMismatchOtpBothReturnSameUnauthorizedResponse() {
        AppSmsLoginRequest request = request("13800138000", "111111");
        when(otpService.consume(OtpPurpose.LOGIN, OtpSubject.login("13800138000"), "111111"))
                .thenReturn(OtpConsumeResult.MISSING, OtpConsumeResult.MISMATCH);

        ResponseVo<?> missing = service.loginSms(request);
        ResponseVo<?> mismatch = service.loginSms(request);

        assertThat(missing.getCode()).isEqualTo(401);
        assertThat(mismatch.getCode()).isEqualTo(401);
        assertThat(missing.getMsg()).isEqualTo(mismatch.getMsg());
    }

    @Test
    void databaseFailureAfterConsumedOtpDoesNotRestoreOtp() {
        AppSmsLoginRequest request = request("13800138000", "111111");
        when(otpService.consume(OtpPurpose.LOGIN, OtpSubject.login("13800138000"), "111111"))
                .thenReturn(OtpConsumeResult.CONSUMED);
        when(customerMapper.selectOne(any())).thenReturn(null);
        when(customerMapper.insert(any(Customer.class))).thenThrow(new RuntimeException("database unavailable"));

        assertThatThrownBy(() -> service.loginSms(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("database unavailable");

        verify(otpService, never()).store(any(), any(), anyString(), any());
    }

    private static AppSmsLoginRequest request(String phone, String code) {
        AppSmsLoginRequest request = new AppSmsLoginRequest();
        request.setPhone(phone);
        request.setCode(code);
        return request;
    }
}
