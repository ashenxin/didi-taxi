package com.sx.passenger.lifecycle.application;

import com.sx.passenger.lifecycle.domain.LifecycleOperationType;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;

import java.util.Optional;

/** 生命周期新快照的持久化边界。 */
public interface LifecycleSnapshotStore {
    /** 按账号、操作类型和幂等键查找历史 Operation。 */
    Optional<LifecycleOperationEntity> findByIdempotency(long customerId,
                                                          LifecycleOperationType type,
                                                          String idempotencyKey);
    /** 在调用方已开启的事务中原子保存快照全部对象。 */
    void persistNew(LifecycleRuntimeSnapshot snapshot);
}
