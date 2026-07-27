package com.sx.passenger.lifecycle.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleStepEntity;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** Step 的加锁读取与运行超时扫描 Mapper。 */
public interface LifecycleStepMapper extends BaseMapper<LifecycleStepEntity> {
    /** 按 Operation 主键加锁读取全部步骤，供编排事务顺序裁决。 */
    List<LifecycleStepEntity> findByOperationIdForUpdate(@Param("operationId") long operationId);

    /** 查找 timeout_at 已过期但仍处于 RUNNING 的步骤。 */
    List<LifecycleStepEntity> findTimedOutRunning(
            @Param("now") LocalDateTime now, @Param("limit") int limit);
}
