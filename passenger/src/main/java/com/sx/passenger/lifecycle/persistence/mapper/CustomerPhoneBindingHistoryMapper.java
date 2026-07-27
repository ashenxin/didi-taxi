package com.sx.passenger.lifecycle.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.passenger.lifecycle.persistence.entity.CustomerPhoneBindingHistoryEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** 手机号绑定历史的定向更新 Mapper。 */
public interface CustomerPhoneBindingHistoryMapper extends BaseMapper<CustomerPhoneBindingHistoryEntity> {
    /** 将当前 ACTIVE 绑定标记为已被换号替代；返回值应为 0 或 1。 */
    @Update("""
            UPDATE customer_phone_binding_history
            SET status = 'REPLACED', valid_to = #{now}, change_operation_no = #{operationNo},
                updated_at = #{now}
            WHERE customer_id = #{customerId} AND status = 'ACTIVE'
            """)
    int replaceActive(@Param("customerId") long customerId,
                      @Param("operationNo") String operationNo,
                      @Param("now") LocalDateTime now);

    /** 注销时释放当前 ACTIVE 绑定，使手机号不再代表该 customer。 */
    @Update("""
            UPDATE customer_phone_binding_history
            SET status = 'RELEASED', valid_to = #{now}, change_operation_no = #{operationNo},
                change_reason = 'ACCOUNT_CANCEL', updated_at = #{now}
            WHERE customer_id = #{customerId} AND status = 'ACTIVE'
            """)
    int releaseActive(@Param("customerId") long customerId,
                      @Param("operationNo") String operationNo,
                      @Param("now") LocalDateTime now);

    /** 查询已有最大绑定代次，供新手机号记录生成下一版本。 */
    @Select("SELECT MAX(binding_version) FROM customer_phone_binding_history WHERE customer_id = #{customerId}")
    Long selectMaxBindingVersion(@Param("customerId") long customerId);
}
