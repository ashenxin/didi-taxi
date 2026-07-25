package com.sx.calculate.lifecycle.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.calculate.lifecycle.model.CalculateAccountLifecycleProjection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CalculateAccountLifecycleProjectionMapper
        extends BaseMapper<CalculateAccountLifecycleProjection> {

    @Select("""
            SELECT customer_id, business_status, lifecycle_status, lifecycle_version,
                   operation_no, source_event_id, row_version, updated_at
              FROM calculate_account_lifecycle_projection
             WHERE customer_id = #{customerId}
             FOR UPDATE
            """)
    CalculateAccountLifecycleProjection selectForUpdate(@Param("customerId") long customerId);

    @Update("""
            UPDATE calculate_account_lifecycle_projection
               SET business_status = #{next.businessStatus},
                   lifecycle_status = #{next.lifecycleStatus},
                   lifecycle_version = #{next.lifecycleVersion},
                   operation_no = #{next.operationNo},
                   source_event_id = #{next.sourceEventId},
                   row_version = row_version + 1,
                   updated_at = #{next.updatedAt}
             WHERE customer_id = #{next.customerId}
               AND row_version = #{expectedRowVersion}
            """)
    int updateWithVersion(@Param("next") CalculateAccountLifecycleProjection next,
                          @Param("expectedRowVersion") long expectedRowVersion);
}
