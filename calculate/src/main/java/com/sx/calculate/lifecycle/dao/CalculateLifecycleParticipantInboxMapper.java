package com.sx.calculate.lifecycle.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.calculate.lifecycle.model.CalculateLifecycleParticipantInbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CalculateLifecycleParticipantInboxMapper
        extends BaseMapper<CalculateLifecycleParticipantInbox> {

    @Select("""
            SELECT id, operation_no, step_code, customer_id, lifecycle_version,
                   request_hash, status, decision, blocker_snapshot, result_snapshot,
                   created_at, updated_at
              FROM calculate_lifecycle_participant_inbox
             WHERE operation_no = #{operationNo} AND step_code = #{stepCode}
            """)
    CalculateLifecycleParticipantInbox find(@Param("operationNo") String operationNo,
                                            @Param("stepCode") String stepCode);

    @Select("""
            SELECT id, operation_no, step_code, customer_id, lifecycle_version,
                   request_hash, status, decision, blocker_snapshot, result_snapshot,
                   created_at, updated_at
              FROM calculate_lifecycle_participant_inbox
             WHERE operation_no = #{operationNo} AND step_code = #{stepCode}
             FOR UPDATE
            """)
    CalculateLifecycleParticipantInbox findForUpdate(@Param("operationNo") String operationNo,
                                                     @Param("stepCode") String stepCode);
}
