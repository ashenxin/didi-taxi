package com.sx.order.lifecycle.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.order.lifecycle.model.OrderAccountLifecycleEventInbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrderAccountLifecycleEventInboxMapper
        extends BaseMapper<OrderAccountLifecycleEventInbox> {

    @Select("""
            SELECT source_event_id, customer_id, lifecycle_version, request_hash, created_at
              FROM order_account_lifecycle_event_inbox
             WHERE source_event_id = #{sourceEventId}
             FOR UPDATE
            """)
    OrderAccountLifecycleEventInbox selectForUpdate(@Param("sourceEventId") String sourceEventId);
}
