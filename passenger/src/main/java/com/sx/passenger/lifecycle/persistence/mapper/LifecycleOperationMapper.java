package com.sx.passenger.lifecycle.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

public interface LifecycleOperationMapper extends BaseMapper<LifecycleOperationEntity> {
    int updateStatusCas(@Param("id") long id,
                        @Param("expectedStatus") String expectedStatus,
                        @Param("expectedRowVersion") long expectedRowVersion,
                        @Param("targetStatus") String targetStatus,
                        @Param("updatedAt") LocalDateTime updatedAt);
}
