package com.sx.passenger.lifecycle.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

public interface LifecycleOperationMapper extends BaseMapper<LifecycleOperationEntity> {
    int updateRestrictedAuthEpoch(@Param("customerId") long customerId,
                                  @Param("operationNo") String operationNo,
                                  @Param("restrictedAuthEpoch") long restrictedAuthEpoch,
                                  @Param("updatedAt") LocalDateTime updatedAt);

    int updateStatusCas(@Param("id") long id,
                        @Param("expectedStatus") String expectedStatus,
                        @Param("expectedRowVersion") long expectedRowVersion,
                        @Param("targetStatus") String targetStatus,
                        @Param("updatedAt") LocalDateTime updatedAt);

    int fenceRequestedCas(@Param("id") long id,
                          @Param("expectedRowVersion") long expectedRowVersion,
                          @Param("restrictedAuthEpoch") long restrictedAuthEpoch,
                          @Param("appliedLifecycleVersion") long appliedLifecycleVersion,
                          @Param("fencedAt") LocalDateTime fencedAt);
}
