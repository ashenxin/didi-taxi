package com.sx.passenger.lifecycle.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.passenger.lifecycle.persistence.entity.CustomerPhoneBindingHistoryEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface CustomerPhoneBindingHistoryMapper extends BaseMapper<CustomerPhoneBindingHistoryEntity> {
    @Update("""
            UPDATE customer_phone_binding_history
            SET status = 'REPLACED', valid_to = #{now}, change_operation_no = #{operationNo},
                updated_at = #{now}
            WHERE customer_id = #{customerId} AND status = 'ACTIVE'
            """)
    int replaceActive(@Param("customerId") long customerId,
                      @Param("operationNo") String operationNo,
                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE customer_phone_binding_history
            SET status = 'RELEASED', valid_to = #{now}, change_operation_no = #{operationNo},
                change_reason = 'ACCOUNT_CANCEL', updated_at = #{now}
            WHERE customer_id = #{customerId} AND status = 'ACTIVE'
            """)
    int releaseActive(@Param("customerId") long customerId,
                      @Param("operationNo") String operationNo,
                      @Param("now") LocalDateTime now);

    @Select("SELECT MAX(binding_version) FROM customer_phone_binding_history WHERE customer_id = #{customerId}")
    Long selectMaxBindingVersion(@Param("customerId") long customerId);
}
