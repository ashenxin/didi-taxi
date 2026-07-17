package com.sx.order.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.order.model.TripOrderSettlement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TripOrderSettlementMapper extends BaseMapper<TripOrderSettlement> {
    @Select("""
            SELECT COUNT(*)
            FROM trip_order o
            LEFT JOIN trip_order_settlement s ON s.order_no = o.order_no
            WHERE o.passenger_id = #{passengerId}
              AND o.is_deleted = 0
              AND (
                o.status NOT IN (5, 6)
                OR (o.status = 5 AND (
                    s.id IS NULL
                    OR s.settlement_status NOT IN ('PAID', 'CLOSED')
                    OR (COALESCE(s.payable_amount, 0) > 0 AND
                        (s.payment_status <> 2 OR s.paid_amount IS NULL
                         OR s.paid_amount < s.payable_amount))
                ))
              )
            """)
    Long countPassengerUnsettledOrders(@Param("passengerId") Long passengerId);
}
