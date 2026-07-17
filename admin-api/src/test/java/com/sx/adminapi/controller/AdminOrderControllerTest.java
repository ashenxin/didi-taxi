package com.sx.adminapi.controller;

import com.sx.adminapi.model.order.AdminOrderDetailVO;
import com.sx.adminapi.model.order.AdminOrderPageVO;
import com.sx.adminapi.service.AdminOrderService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertSame;

class AdminOrderControllerTest {
    private final StubAdminOrderService service = new StubAdminOrderService();
    private final AdminOrderController controller = new AdminOrderController(service);

    @Test
    void returnsOrderPageFromService() {
        AdminOrderPageVO page = new AdminOrderPageVO();
        service.page = page;

        assertSame(page, controller.page(null, null, null, null, null, null, null, 1, 10).getData());
    }

    @Test
    void returnsOrderDetailFromService() {
        AdminOrderDetailVO detail = new AdminOrderDetailVO();
        service.detail = detail;

        assertSame(detail, controller.detail("ORDER-1").getData());
    }

    private static final class StubAdminOrderService extends AdminOrderService {
        private AdminOrderPageVO page;
        private AdminOrderDetailVO detail;

        private StubAdminOrderService() {
            super(null, null, null);
        }

        @Override
        public AdminOrderPageVO page(String orderNo, String phone, String provinceCode, String cityCode,
                                     Integer status, LocalDateTime createdAtStart, LocalDateTime createdAtEnd,
                                     Integer pageNo, Integer pageSize) {
            return page;
        }

        @Override
        public AdminOrderDetailVO detail(String orderNo) {
            return detail;
        }
    }
}
