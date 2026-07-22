package com.sx.passengerapi.service;

import com.sx.passengerapi.client.CalculateClient;
import com.sx.passengerapi.client.OrderClient;
import com.sx.passengerapi.client.dto.AppAccountCancelConfirmRequest;
import com.sx.passengerapi.client.dto.AppAccountCancelResult;
import com.sx.passengerapi.client.dto.AppPhoneChangeConfirmRequest;
import com.sx.passengerapi.client.dto.AppPhoneChangeResult;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.ordercore.OrderPageData;
import com.sx.passengerapi.model.ordercore.UnsettledOrderCheckResult;
import com.sx.passengerapi.model.settings.AccountCancelConfirmRequest;
import com.sx.passengerapi.model.settings.AccountCancelResultVO;
import com.sx.passengerapi.model.settings.PhoneChangeConfirmRequest;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PassengerSettingsServiceTest {
    private final OrderClient orderClient = mock(OrderClient.class);
    private final CalculateClient calculateClient = mock(CalculateClient.class);
    private final PassengerLifecycleOrchestrator lifecycleOrchestrator = mock(PassengerLifecycleOrchestrator.class);
    private final PassengerSettingsService service = new PassengerSettingsService(
            mock(com.sx.passengerapi.client.PassengerCoreSettingsClient.class),
            orderClient, calculateClient, lifecycleOrchestrator);

    @Test
    void successfulPhoneChangeClosesOldWsWithoutStartingSaga() {
        AppPhoneChangeResult changed = new AppPhoneChangeResult();
        changed.setChanged(true);
        changed.setRequireLogin(true);
        changed.setMaskedNewPhone("****8000");
        when(lifecycleOrchestrator.confirmPhoneChange(any())).thenReturn(changed);

        service.confirmPhoneChange(10001L, phoneChangeRequest());

        verify(lifecycleOrchestrator).confirmPhoneChange(any(AppPhoneChangeConfirmRequest.class));
        verifyNoInteractions(orderClient, calculateClient);
    }

    @Test
    void confirmAccountCancelKeepsCancellationSuccessfulWhenBenefitCleanupFails() {
        when(orderClient.pageOrders(eq(10001L), eq(1), eq(100))).thenReturn(ResponseVo.success(emptyOrderPage()));
        UnsettledOrderCheckResult unsettled = new UnsettledOrderCheckResult();
        unsettled.setExists(false);
        when(orderClient.unsettledExists(10001L)).thenReturn(ResponseVo.success(unsettled));
        when(calculateClient.lockedCouponsExists(10001L)).thenReturn(ResponseVo.success(false));
        when(lifecycleOrchestrator.confirmAccountCancel(any())).thenReturn(cancelledResult());
        when(calculateClient.invalidateCouponsByPassenger(any())).thenReturn(ResponseVo.success(0));
        when(calculateClient.clearBenefitPointsByAccountCancel(any()))
                .thenReturn(new ResponseVo<>(502, "福利积分服务暂时不可用"));

        AccountCancelResultVO result = service.confirmAccountCancel(10001L, cancelRequest());

        assertThat(result.getCancelled()).isTrue();
        assertThat(result.getRequireLogin()).isTrue();
        verify(lifecycleOrchestrator).confirmAccountCancel(any(AppAccountCancelConfirmRequest.class));
    }

    private static OrderPageData emptyOrderPage() {
        OrderPageData data = new OrderPageData();
        data.setTotal(0);
        data.setPageNo(1);
        data.setPageSize(100);
        data.setList(Collections.emptyList());
        return data;
    }

    private static AppAccountCancelResult cancelledResult() {
        AppAccountCancelResult result = new AppAccountCancelResult();
        result.setCancelled(true);
        result.setRequireLogin(true);
        return result;
    }

    private static AccountCancelConfirmRequest cancelRequest() {
        AccountCancelConfirmRequest request = new AccountCancelConfirmRequest();
        request.setCode("123456");
        request.setConfirm(true);
        return request;
    }

    private static PhoneChangeConfirmRequest phoneChangeRequest() {
        PhoneChangeConfirmRequest request = new PhoneChangeConfirmRequest();
        request.setNewPhone("13800138000");
        request.setCode("123456");
        return request;
    }
}
