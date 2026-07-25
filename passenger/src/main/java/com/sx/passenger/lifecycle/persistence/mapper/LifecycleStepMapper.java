package com.sx.passenger.lifecycle.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleStepEntity;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface LifecycleStepMapper extends BaseMapper<LifecycleStepEntity> {
    List<LifecycleStepEntity> findByOperationIdForUpdate(@Param("operationId") long operationId);

    List<LifecycleStepEntity> findTimedOutRunning(
            @Param("now") LocalDateTime now, @Param("limit") int limit);
}
