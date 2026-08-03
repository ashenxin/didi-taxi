package com.sx.passenger.app;

import com.sx.passenger.app.dto.AppAuthCustomerBrief;
import com.sx.passenger.app.dto.AppLoginPasswordRequest;
import com.sx.passenger.app.dto.AppSmsLoginRequest;
import com.sx.passenger.auth.otp.AtomicOtpService;
import com.sx.passenger.auth.otp.OtpConsumeResult;
import com.sx.passenger.auth.otp.OtpPurpose;
import com.sx.passenger.auth.otp.OtpSubject;
import com.sx.passenger.auth.session.PassengerAuthEpochService;
import com.sx.passenger.common.vo.ResponseVo;
import com.sx.passenger.dao.CustomerEntityMapper;
import com.sx.passenger.lifecycle.application.LifecycleStatusOutboxAppender;
import com.sx.passenger.lifecycle.application.phone.PhoneBindingValueFactory;
import com.sx.passenger.lifecycle.persistence.entity.CustomerPhoneBindingHistoryEntity;
import com.sx.passenger.lifecycle.persistence.mapper.CustomerPhoneBindingHistoryMapper;
import com.sx.passenger.model.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

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
    private final PassengerAuthEpochService authEpochService = mock(PassengerAuthEpochService.class);
    private final LifecycleStatusOutboxAppender lifecycleOutboxes =
            mock(LifecycleStatusOutboxAppender.class);
    private final CustomerPhoneBindingHistoryMapper phoneBindings =
            mock(CustomerPhoneBindingHistoryMapper.class);
    private final PhoneBindingValueFactory phoneBindingValues = new PhoneBindingValueFactory();
    private AppCustomerAuthService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOperations);
        service = new AppCustomerAuthService(
                customerMapper, redis, new AppCustomerAuthProperties(), otpService,
                authEpochService, lifecycleOutboxes, phoneBindings, phoneBindingValues);
    }

    @Test
    void passwordLoginReturnsDatabaseIssuedAuthenticationMaterial() {
        Customer customer = new Customer()
                .setId(10001L)
                .setPhone("13800138000")
                .setPasswordHash(new BCryptPasswordEncoder().encode("secret"))
                .setStatus(0)
                .setLifecycleStatus("ACTIVE")
                .setLifecycleVersion(0L)
                .setAuthEpoch(3L)
                .setIsDeleted(0);
        AppAuthCustomerBrief issued = brief(10001L, 4L, "NORMAL", null);
        when(customerMapper.selectOne(any())).thenReturn(customer);
        when(authEpochService.completeAuthentication(10001L)).thenReturn(issued);
        AppLoginPasswordRequest request = new AppLoginPasswordRequest();
        request.setPhone("13800138000");
        request.setPassword("secret");

        ResponseVo<AppAuthCustomerBrief> response = service.loginPassword(request);

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData()).isSameAs(issued);
        assertThat(response.getData().getAuthEpoch()).isEqualTo(4L);
        verify(authEpochService).completeAuthentication(10001L);
    }

    @Test
    void newSmsCustomerInitializesLifecycleBeforeAuthenticationCompletes() {
        when(otpService.consume(OtpPurpose.LOGIN, OtpSubject.login("13800138000"), "111111"))
                .thenReturn(OtpConsumeResult.CONSUMED);
        when(customerMapper.selectOne(any())).thenReturn(null);
        when(customerMapper.insert(any(Customer.class))).thenAnswer(invocation -> {
            invocation.<Customer>getArgument(0).setId(10001L);
            return 1;
        });
        AppAuthCustomerBrief issued = brief(10001L, 1L, "NORMAL", null);
        when(authEpochService.completeAuthentication(10001L)).thenReturn(issued);

        ResponseVo<AppAuthCustomerBrief> response = service.loginSms(request("13800138000", "111111"));

        assertThat(response.getData()).isSameAs(issued);
        verify(customerMapper).insert(org.mockito.ArgumentMatchers.<Customer>argThat(customer ->
                "ACTIVE".equals(customer.getLifecycleStatus())
                        && Long.valueOf(0L).equals(customer.getLifecycleVersion())
                        && Long.valueOf(0L).equals(customer.getAuthEpoch())));
        verify(phoneBindings).insert(org.mockito.ArgumentMatchers.<CustomerPhoneBindingHistoryEntity>argThat(binding ->
                Long.valueOf(10001L).equals(binding.getCustomerId())
                        && Long.valueOf(1L).equals(binding.getBindingVersion())
                        && "ACTIVE".equals(binding.getStatus())
                        && "REGISTER".equals(binding.getChangeReason())
                        && binding.getChangeOperationNo() == null));
        verify(lifecycleOutboxes).appendInitialActive(eq(10001L), any());
        verify(authEpochService).completeAuthentication(10001L);
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

    private static AppAuthCustomerBrief brief(long customerId, long authEpoch, String scope, String operationNo) {
        AppAuthCustomerBrief brief = new AppAuthCustomerBrief();
        brief.setId(customerId);
        brief.setAuthEpoch(authEpoch);
        brief.setScope(scope);
        brief.setOperationNo(operationNo);
        return brief;
    }
}
