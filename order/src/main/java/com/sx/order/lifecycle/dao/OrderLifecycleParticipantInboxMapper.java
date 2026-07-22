package com.sx.order.lifecycle.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.order.lifecycle.model.OrderLifecycleParticipantInbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrderLifecycleParticipantInboxMapper
        extends BaseMapper<OrderLifecycleParticipantInbox> {

    @Select("""
            SELECT id, operation_no, step_code, customer_id, request_hash, status,
                   decision, blocker_snapshot, created_at, updated_at
              FROM order_lifecycle_participant_inbox
             WHERE operation_no = #{operationNo} AND step_code = #{stepCode}
             FOR UPDATE
            """)
    OrderLifecycleParticipantInbox findForUpdate(@Param("operationNo") String operationNo,
                                                  @Param("stepCode") String stepCode);

    @Select("""
            SELECT id, operation_no, step_code, customer_id, request_hash, status,
                   decision, blocker_snapshot, created_at, updated_at
              FROM order_lifecycle_participant_inbox
             WHERE operation_no = #{operationNo} AND step_code = #{stepCode}
            """)
    OrderLifecycleParticipantInbox find(@Param("operationNo") String operationNo,
                                         @Param("stepCode") String stepCode);
}
