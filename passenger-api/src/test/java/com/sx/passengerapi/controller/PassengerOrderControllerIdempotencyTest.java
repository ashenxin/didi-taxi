package com.sx.passengerapi.controller;

import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.model.order.CreateAndAssignOrderBody;
import com.sx.passengerapi.model.order.CreateAndAssignOrderResult;
import com.sx.passengerapi.model.order.CreateOrderResultV1;
import com.sx.passengerapi.model.order.CancelOrderRequest;
import com.sx.passengerapi.model.ordercore.OrderActionResult;
import com.sx.passengerapi.service.PassengerOrderService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PassengerOrderControllerIdempotencyTest {

    private final PassengerOrderService service = mock(PassengerOrderService.class);
    private final PassengerOrderController controller = new PassengerOrderController(service);

    @Test
    void createAndAssignRequiresIdempotencyKey() {
        assertThatThrownBy(() -> controller.createAndAssign(10001L, " ", new CreateAndAssignOrderBody()))
                .isInstanceOf(BizErrorException.class)
                .hasMessageContaining("Idempotency-Key不能为空");
    }

    @Test
    void createAndAssignPassesIdempotencyKeyToService() {
        CreateAndAssignOrderResult result = new CreateAndAssignOrderResult();
        result.setOrderNo("O1");
        when(service.createAndAssign(any(CreateAndAssignOrderBody.class), any())).thenReturn(result);

        var resp = controller.createAndAssign(10001L, "idem-1", new CreateAndAssignOrderBody());

        assertThat(resp.getData().getOrderNo()).isEqualTo("O1");
        verify(service).createAndAssign(any(CreateAndAssignOrderBody.class), org.mockito.ArgumentMatchers.eq("idem-1"));
    }

    @Test
    void createTwoPhaseRequiresIdempotencyKey() {
        assertThatThrownBy(() -> controller.createTwoPhase(10001L, null, new CreateAndAssignOrderBody()))
                .isInstanceOf(BizErrorException.class)
                .hasMessageContaining("Idempotency-Key不能为空");
    }

    @Test
    void createTwoPhasePassesIdempotencyKeyToService() {
        CreateOrderResultV1 result = new CreateOrderResultV1();
        result.setOrderNo("O2");
        when(service.createTwoPhase(any(CreateAndAssignOrderBody.class), any())).thenReturn(result);

        var resp = controller.createTwoPhase(10001L, "idem-2", new CreateAndAssignOrderBody());

        assertThat(resp.getData().getOrderNo()).isEqualTo("O2");
        verify(service).createTwoPhase(any(CreateAndAssignOrderBody.class), org.mockito.ArgumentMatchers.eq("idem-2"));
    }

    @Test
    void cancelRequiresIdempotencyKey() {
        CancelOrderRequest body = new CancelOrderRequest();
        body.setPassengerId(10001L);

        assertThatThrownBy(() -> controller.cancelOrder("O3", 10001L, " ", body))
                .isInstanceOf(BizErrorException.class)
                .hasMessageContaining("Idempotency-Key不能为空");
    }

    @Test
    void cancelPassesTrimmedKeyAndReturnsReplayResult() {
        CancelOrderRequest body = new CancelOrderRequest();
        body.setPassengerId(10001L);
        body.setCancelReason("行程有变");
        when(service.cancelOrder("O3", body, "cancel-key"))
                .thenReturn(new OrderActionResult(true));

        var resp = controller.cancelOrder("O3", 10001L, " cancel-key ", body);

        assertThat(resp.getData().replayed()).isTrue();
        verify(service).cancelOrder("O3", body, "cancel-key");
    }
}
