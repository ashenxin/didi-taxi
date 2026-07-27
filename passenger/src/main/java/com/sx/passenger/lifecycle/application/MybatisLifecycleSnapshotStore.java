package com.sx.passenger.lifecycle.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.passenger.lifecycle.domain.LifecycleOperationType;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleEventMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOperationMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOutboxMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleStepMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 基于 MyBatis 的快照存储实现。
 *
 * <p>保存方法强制要求外部事务，保证 Operation、Steps、Event 和 Outbox
 * 要么全部提交，要么全部回滚。
 */
@Service
public class MybatisLifecycleSnapshotStore implements LifecycleSnapshotStore {
    private final LifecycleOperationMapper operations;
    private final LifecycleStepMapper steps;
    private final LifecycleEventMapper events;
    private final LifecycleOutboxMapper outboxes;

    public MybatisLifecycleSnapshotStore(LifecycleOperationMapper operations, LifecycleStepMapper steps,
                                         LifecycleEventMapper events, LifecycleOutboxMapper outboxes) {
        this.operations = operations;
        this.steps = steps;
        this.events = events;
        this.outboxes = outboxes;
    }

    @Override
    public Optional<LifecycleOperationEntity> findByIdempotency(long customerId,
                                                                 LifecycleOperationType type,
                                                                 String idempotencyKey) {
        return Optional.ofNullable(operations.selectOne(Wrappers.<LifecycleOperationEntity>lambdaQuery()
                .eq(LifecycleOperationEntity::getCustomerId, customerId)
                .eq(LifecycleOperationEntity::getOperationType, type.name())
                .eq(LifecycleOperationEntity::getIdempotencyKey, idempotencyKey)));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void persistNew(LifecycleRuntimeSnapshot snapshot) {
        // 先插入聚合根取得主键，再回填到步骤、审计事件和 Outbox。
        if (operations.insert(snapshot.operation()) != 1) {
            throw new IllegalStateException("Failed to insert lifecycle operation");
        }
        long operationId = snapshot.operation().getId();
        snapshot.steps().forEach(step -> {
            step.setOperationId(operationId);
            if (steps.insert(step) != 1) throw new IllegalStateException("Failed to insert lifecycle step");
        });
        snapshot.requestedEvent().setOperationId(operationId);
        if (events.insert(snapshot.requestedEvent()) != 1) {
            throw new IllegalStateException("Failed to insert lifecycle event");
        }
        snapshot.requestedOutbox().setOperationId(operationId);
        if (outboxes.insert(snapshot.requestedOutbox()) != 1) {
            throw new IllegalStateException("Failed to insert lifecycle outbox");
        }
    }
}
