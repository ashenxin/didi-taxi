package com.sx.order.controller;

import com.sx.order.common.vo.ResponseVo;
import com.sx.order.dao.TripOrderEntityMapper;
import com.sx.order.model.TripOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class TripOrderPaginationTest {

    @Autowired
    private TripOrderController controller;
    @Autowired
    private TripOrderEntityMapper tripOrderMapper;

    @BeforeEach
    void clean() {
        tripOrderMapper.delete(null);
    }

    /**
     * 直接验证数据库分页：每页只返回 pageSize 条，total 来自 count；
     * 同一 createdAt 下按 id 倒序，保证跨页顺序稳定。
     */
    @Test
    void paginatesInDatabaseWithStableOrdering() {
        for (int index = 1; index <= 5; index++) {
            tripOrderMapper.insert(order(index));
        }

        Map<String, Object> firstPage = responseData(1, 2);
        assertThat(((Number) firstPage.get("total")).longValue()).isEqualTo(5L);
        assertThat(((Number) firstPage.get("pageNo")).intValue()).isEqualTo(1);
        assertThat(((Number) firstPage.get("pageSize")).intValue()).isEqualTo(2);
        List<TripOrder> firstPageRows = rows(firstPage);
        assertThat(firstPageRows)
                .extracting(TripOrder::getOrderNo)
                .containsExactly("ORDER-5", "ORDER-4");

        Map<String, Object> thirdPage = responseData(3, 2);
        assertThat(((Number) thirdPage.get("total")).longValue()).isEqualTo(5L);
        assertThat(rows(thirdPage))
                .extracting(TripOrder::getOrderNo)
                .containsExactly("ORDER-1");
    }

    @SuppressWarnings("unchecked")
    private static List<TripOrder> rows(Map<String, Object> data) {
        return (List<TripOrder>) data.get("list");
    }

    private Map<String, Object> responseData(int pageNo, int pageSize) {
        ResponseVo<Map<String, Object>> response = controller.page(
                null, null, null, null, null, null, null, pageNo, pageSize);
        return response.getData();
    }

    private static TripOrder order(int index) {
        LocalDateTime createdAt = LocalDateTime.of(2026, 1, 1, 0, 0);
        return new TripOrder()
                .setOrderNo("ORDER-" + index)
                .setPassengerId(10_000L + index)
                .setProductCode("ECONOMY")
                .setProvinceCode("330000")
                .setCityCode("330100")
                .setOriginAddress("杭州东站")
                .setOriginLat(new BigDecimal("30.2912000"))
                .setOriginLng(new BigDecimal("120.2120000"))
                .setDestAddress("龙翔桥")
                .setDestLat(new BigDecimal("30.2592000"))
                .setDestLng(new BigDecimal("120.1640000"))
                .setStatus(0)
                .setOfferRound(0)
                .setCreatedAt(createdAt)
                .setUpdatedAt(createdAt)
                .setIsDeleted(0);
    }
}
