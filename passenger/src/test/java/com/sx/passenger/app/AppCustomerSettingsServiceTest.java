package com.sx.passenger.app;

import com.sx.passenger.app.dto.AppAccountCancelConfirmRequest;
import com.sx.passenger.app.dto.AppAccountCancelResult;
import com.sx.passenger.app.dto.AppPhoneChangeConfirmRequest;
import com.sx.passenger.app.dto.AppPhoneChangeResult;
import com.sx.passenger.app.dto.AppPhoneChangeSmsSendRequest;
import com.sx.passenger.app.dto.AppSettingsProfileResponse;
import com.sx.passenger.auth.otp.AtomicOtpService;
import com.sx.passenger.auth.otp.OtpConsumeResult;
import com.sx.passenger.auth.otp.OtpPurpose;
import com.sx.passenger.auth.otp.OtpSubject;
import com.sx.passenger.auth.metrics.PassengerAuthMetrics;
import com.sx.passenger.common.vo.ResponseVo;
import com.sx.passenger.dao.CustomerEntityMapper;
import com.sx.passenger.model.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppCustomerSettingsServiceTest {

    private final CustomerEntityMapper customerMapper = mock(CustomerEntityMapper.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final AtomicOtpService otpService = mock(AtomicOtpService.class);
    private final PassengerAuthMetrics metrics = mock(PassengerAuthMetrics.class);
    private AppCustomerSettingsService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOperations);
        service = new AppCustomerSettingsService(
                customerMapper, redis, new AppCustomerAuthProperties(), otpService, metrics);
    }

    @Test
    void profileReturnsMaskedPhoneForActiveCustomer() {
        when(customerMapper.selectOne(any())).thenReturn(customer(10001L, "13812345678"));

        ResponseVo<AppSettingsProfileResponse> response = service.profile(10001L);

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData().getMaskedPhone()).isEqualTo("138****5678");
        assertThat(response.getData().getCustomerId()).isEqualTo(10001L);
    }

    @Test
    void phoneChangeRejectsNumberOccupiedAfterOtpWasIssued() {
        Customer current = customer(10001L, "13812345678");
        Customer occupied = customer(10002L, "13912345678");
        when(customerMapper.selectOne(any())).thenReturn(current, occupied);
        when(otpService.consume(OtpPurpose.PHONE_CHANGE_NEW_PHONE,
                OtpSubject.phoneChange(10001L, "13912345678", 9L), "123456"))
                .thenReturn(OtpConsumeResult.CONSUMED);
        AppPhoneChangeConfirmRequest request = new AppPhoneChangeConfirmRequest();
        request.setCustomerId(10001L);
        request.setNewPhone("13912345678");
        request.setCode("123456");

        ResponseVo<?> response = service.confirmPhoneChange(request);

        assertThat(response.getCode()).isEqualTo(409);
        verify(customerMapper, never()).update(any(), any());
        verify(otpService, never()).store(any(), any(), anyString(), any());
    }

    @Test
    void accountCancellationRequiresMatchingOtpBeforeDeletingCustomer() {
        when(customerMapper.selectOne(any())).thenReturn(customer(10001L, "13812345678"));
        when(otpService.consume(OtpPurpose.ACCOUNT_CANCEL, OtpSubject.accountCancel(10001L, 9L), "123456"))
                .thenReturn(OtpConsumeResult.MISMATCH);
        AppAccountCancelConfirmRequest request = new AppAccountCancelConfirmRequest();
        request.setCustomerId(10001L);
        request.setCode("123456");
        request.setConfirm(true);

        ResponseVo<?> response = service.confirmAccountCancel(request);

        assertThat(response.getCode()).isEqualTo(401);
        verify(customerMapper, never()).update(any(), any());
    }

    @Test
    void phoneChangeSmsStoresOtpUnderPurposeAndLifecycleVersion() {
        Customer current = customer(10001L, "13812345678");
        when(customerMapper.selectOne(any())).thenReturn(current, null);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(valueOperations.increment(anyString())).thenReturn(1L);
        AppPhoneChangeSmsSendRequest request = new AppPhoneChangeSmsSendRequest();
        request.setCustomerId(10001L);
        request.setNewPhone("13912345678");

        ResponseVo<?> response = service.sendPhoneChangeSms(request);

        assertThat(response.getCode()).isEqualTo(200);
        verify(otpService).store(eq(OtpPurpose.PHONE_CHANGE_NEW_PHONE),
                eq(OtpSubject.phoneChange(10001L, "13912345678", 9L)), anyString(), eq(Duration.ofSeconds(300)));
    }

    @Test
    void phoneChangeSmsAcceptsInitialLifecycleVersion() {
        Customer current = customerWithLifecycleVersion(10001L, "13812345678", 0L);
        when(customerMapper.selectOne(any())).thenReturn(current, null);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(valueOperations.increment(anyString())).thenReturn(1L);
        AppPhoneChangeSmsSendRequest request = new AppPhoneChangeSmsSendRequest();
        request.setCustomerId(10001L);
        request.setNewPhone("13912345678");

        ResponseVo<?> response = service.sendPhoneChangeSms(request);

        assertThat(response.getCode()).isEqualTo(200);
        verify(otpService).store(eq(OtpPurpose.PHONE_CHANGE_NEW_PHONE),
                eq(OtpSubject.phoneChange(10001L, "13912345678", 0L)), anyString(), eq(Duration.ofSeconds(300)));
    }

    @Test
    void phoneChangeConfirmationAcceptsInitialLifecycleVersion() {
        Customer current = customerWithLifecycleVersion(10001L, "13812345678", 0L);
        current.setAuthEpoch(4L);
        when(customerMapper.selectOne(any())).thenReturn(current, null);
        when(otpService.consume(OtpPurpose.PHONE_CHANGE_NEW_PHONE,
                OtpSubject.phoneChange(10001L, "13912345678", 0L), "123456"))
                .thenReturn(OtpConsumeResult.CONSUMED);
        when(customerMapper.changePhoneCas(10001L, "13912345678", 0L)).thenReturn(1);
        when(customerMapper.selectAuthEpochById(10001L)).thenReturn(12L);
        AppPhoneChangeConfirmRequest request = new AppPhoneChangeConfirmRequest();
        request.setCustomerId(10001L);
        request.setNewPhone("13912345678");
        request.setCode("123456");

        ResponseVo<AppPhoneChangeResult> response = service.confirmPhoneChange(request);

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData().getCustomerId()).isEqualTo(10001L);
        assertThat(response.getData().getNewAuthEpoch()).isEqualTo(12L);
        assertThat(response.getData().getRequireLogin()).isTrue();
        assertThat(response.getData().getRevocationReason()).isEqualTo("phone_changed");
        verify(customerMapper).changePhoneCas(10001L, "13912345678", 0L);
        verify(customerMapper, never()).update(any(), any());
        verify(metrics).observeEpochBump(PassengerAuthMetrics.EpochCause.PHONE_CHANGE);
    }

    @Test
    void phoneChangeReturnsConflictWhenLifecycleCasLoses() {
        Customer current = customer(10001L, "13812345678");
        when(customerMapper.selectOne(any())).thenReturn(current, null);
        when(otpService.consume(OtpPurpose.PHONE_CHANGE_NEW_PHONE,
                OtpSubject.phoneChange(10001L, "13912345678", 9L), "123456"))
                .thenReturn(OtpConsumeResult.CONSUMED);
        when(customerMapper.changePhoneCas(10001L, "13912345678", 9L)).thenReturn(0);
        AppPhoneChangeConfirmRequest request = new AppPhoneChangeConfirmRequest();
        request.setCustomerId(10001L);
        request.setNewPhone("13912345678");
        request.setCode("123456");

        ResponseVo<?> response = service.confirmPhoneChange(request);

        assertThat(response.getCode()).isEqualTo(409);
        verify(metrics).epochBump(PassengerAuthMetrics.EpochCause.PHONE_CHANGE,
                PassengerAuthMetrics.OperationResult.CONFLICT);
    }

    @Test
    void accountCancelSmsStoresOtpUnderPurposeAndLifecycleVersion() {
        Customer current = customer(10001L, "13812345678");
        when(customerMapper.selectOne(any())).thenReturn(current);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(valueOperations.increment(anyString())).thenReturn(1L);

        ResponseVo<?> response = service.sendAccountCancelSms(10001L);

        assertThat(response.getCode()).isEqualTo(200);
        verify(otpService).store(eq(OtpPurpose.ACCOUNT_CANCEL), eq(OtpSubject.accountCancel(10001L, 9L)),
                anyString(), eq(Duration.ofSeconds(300)));
    }

    @Test
    void databaseFailureAfterConsumedAccountCancelDoesNotRestoreOtp() {
        Customer current = customer(10001L, "13812345678");
        when(customerMapper.selectOne(any())).thenReturn(current);
        when(otpService.consume(OtpPurpose.ACCOUNT_CANCEL, OtpSubject.accountCancel(10001L, 9L), "123456"))
                .thenReturn(OtpConsumeResult.CONSUMED);
        when(customerMapper.cancelAccountCas(eq(10001L), eq(9L), any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("database unavailable"));
        AppAccountCancelConfirmRequest request = new AppAccountCancelConfirmRequest();
        request.setCustomerId(10001L);
        request.setCode("123456");
        request.setConfirm(true);

        assertThatThrownBy(() -> service.confirmAccountCancel(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("database unavailable");

        verify(otpService, never()).store(any(), any(), anyString(), any());
        verify(metrics, never()).observeEpochBump(PassengerAuthMetrics.EpochCause.ACCOUNT_CANCEL);
    }

    @Test
    void accountCancellationUsesLifecycleCasAndReturnsConflictWhenItLoses() {
        Customer current = customer(10001L, "13812345678");
        when(customerMapper.selectOne(any())).thenReturn(current);
        when(otpService.consume(OtpPurpose.ACCOUNT_CANCEL, OtpSubject.accountCancel(10001L, 9L), "123456"))
                .thenReturn(OtpConsumeResult.CONSUMED);
        when(customerMapper.cancelAccountCas(eq(10001L), eq(9L), any(LocalDateTime.class))).thenReturn(0);
        AppAccountCancelConfirmRequest request = new AppAccountCancelConfirmRequest();
        request.setCustomerId(10001L);
        request.setCode("123456");
        request.setConfirm(true);

        ResponseVo<?> response = service.confirmAccountCancel(request);

        assertThat(response.getCode()).isEqualTo(409);
        verify(customerMapper, never()).update(any(), any());
        verify(metrics).epochBump(PassengerAuthMetrics.EpochCause.ACCOUNT_CANCEL,
                PassengerAuthMetrics.OperationResult.CONFLICT);
    }

    @Test
    void successfulAccountCancellationReturnsExplicitRevocationFacts() {
        Customer current = customer(10001L, "13812345678");
        current.setAuthEpoch(7L);
        when(customerMapper.selectOne(any())).thenReturn(current);
        when(otpService.consume(OtpPurpose.ACCOUNT_CANCEL, OtpSubject.accountCancel(10001L, 9L), "123456"))
                .thenReturn(OtpConsumeResult.CONSUMED);
        when(customerMapper.cancelAccountCas(eq(10001L), eq(9L), any(LocalDateTime.class))).thenReturn(1);
        when(customerMapper.selectAuthEpochById(10001L)).thenReturn(8L);
        AppAccountCancelConfirmRequest request = new AppAccountCancelConfirmRequest();
        request.setCustomerId(10001L);
        request.setCode("123456");
        request.setConfirm(true);

        ResponseVo<AppAccountCancelResult> response = service.confirmAccountCancel(request);

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData().getCustomerId()).isEqualTo(10001L);
        assertThat(response.getData().getNewAuthEpoch()).isEqualTo(8L);
        assertThat(response.getData().getRequireLogin()).isTrue();
        assertThat(response.getData().getRevocationReason()).isEqualTo("account_cancelled");
    }

    private static Customer customer(Long id, String phone) {
        return customerWithLifecycleVersion(id, phone, 9L);
    }

    private static Customer customerWithLifecycleVersion(Long id, String phone, Long lifecycleVersion) {
        return new Customer()
                .setId(id)
                .setPhone(phone)
                .setStatus(0)
                .setLifecycleVersion(lifecycleVersion)
                .setIsDeleted(0);
    }
}
