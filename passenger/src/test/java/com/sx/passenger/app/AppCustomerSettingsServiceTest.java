package com.sx.passenger.app;

import com.sx.passenger.app.dto.AppAccountCancelConfirmRequest;
import com.sx.passenger.app.dto.AppPhoneChangeConfirmRequest;
import com.sx.passenger.app.dto.AppSettingsProfileResponse;
import com.sx.passenger.common.vo.ResponseVo;
import com.sx.passenger.dao.CustomerEntityMapper;
import com.sx.passenger.model.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppCustomerSettingsServiceTest {

    private final CustomerEntityMapper customerMapper = mock(CustomerEntityMapper.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private AppCustomerSettingsService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOperations);
        service = new AppCustomerSettingsService(customerMapper, redis, new AppCustomerAuthProperties());
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
        when(valueOperations.get("app:settings:phone-change:new:otp:10001:13912345678"))
                .thenReturn("123456");
        AppPhoneChangeConfirmRequest request = new AppPhoneChangeConfirmRequest();
        request.setCustomerId(10001L);
        request.setNewPhone("13912345678");
        request.setCode("123456");

        ResponseVo<?> response = service.confirmPhoneChange(request);

        assertThat(response.getCode()).isEqualTo(409);
        verify(customerMapper, never()).update(any(), any());
        verify(redis, never()).delete(any(String.class));
    }

    @Test
    void accountCancellationRequiresMatchingOtpBeforeDeletingCustomer() {
        when(customerMapper.selectOne(any())).thenReturn(customer(10001L, "13812345678"));
        when(valueOperations.get("app:settings:account-cancel:otp:10001")).thenReturn("654321");
        AppAccountCancelConfirmRequest request = new AppAccountCancelConfirmRequest();
        request.setCustomerId(10001L);
        request.setCode("123456");
        request.setConfirm(true);

        ResponseVo<?> response = service.confirmAccountCancel(request);

        assertThat(response.getCode()).isEqualTo(401);
        verify(customerMapper, never()).update(any(), any());
        verify(redis, never()).delete(any(String.class));
    }

    private static Customer customer(Long id, String phone) {
        return new Customer()
                .setId(id)
                .setPhone(phone)
                .setStatus(0)
                .setIsDeleted(0);
    }
}
