package com.sx.wallet.lifecycle.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.wallet.lifecycle.model.WalletLifecycleProjection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WalletLifecycleProjectionMapper extends BaseMapper<WalletLifecycleProjection> {
    @Select("SELECT * FROM wallet_account_lifecycle_projection WHERE customer_id=#{id} FOR UPDATE")
    WalletLifecycleProjection selectForUpdate(@Param("id") long customerId);

    @Update("""
            UPDATE wallet_account_lifecycle_projection
            SET business_status=#{p.businessStatus}, lifecycle_status=#{p.lifecycleStatus},
                lifecycle_version=#{p.lifecycleVersion}, operation_no=#{p.operationNo},
                source_event_id=#{p.sourceEventId}, row_version=row_version+1,
                updated_at=#{p.updatedAt}
            WHERE customer_id=#{p.customerId} AND row_version=#{expected}
            """)
    int updateWithVersion(@Param("p") WalletLifecycleProjection projection,
                          @Param("expected") long expected);
}
