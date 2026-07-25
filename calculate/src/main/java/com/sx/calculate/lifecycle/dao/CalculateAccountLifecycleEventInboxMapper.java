package com.sx.calculate.lifecycle.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.calculate.lifecycle.model.CalculateAccountLifecycleEventInbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CalculateAccountLifecycleEventInboxMapper
        extends BaseMapper<CalculateAccountLifecycleEventInbox> {

    @Select("""
            SELECT source_event_id, customer_id, lifecycle_version, request_hash, created_at
              FROM calculate_account_lifecycle_event_inbox
             WHERE source_event_id = #{sourceEventId}
             FOR UPDATE
            """)
    CalculateAccountLifecycleEventInbox selectForUpdate(@Param("sourceEventId") String sourceEventId);
}
