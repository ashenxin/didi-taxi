package com.sx.passenger.lifecycle.application;

import com.sx.passenger.lifecycle.domain.LifecycleActorType;
import com.sx.passenger.lifecycle.domain.LifecycleOperationType;

import java.time.Instant;

/**
 * 创建运行时快照的完整输入。
 *
 * @param customerId 账号主键
 * @param operationType 换号或注销
 * @param idempotencyKey 调用级幂等键
 * @param requestHash 规范化请求 SHA-256
 * @param expectedLifecycleVersion 预期账号生命周期版本
 * @param actorType 发起主体类型
 * @param actorId 发起主体审计标识
 * @param traceId 调用链标识
 * @param sanitizedRequestContextJson 已脱敏请求上下文
 * @param requestedAt 请求发生时间
 */
public record CreateLifecycleSnapshotCommand(long customerId,
                                             LifecycleOperationType operationType,
                                             String idempotencyKey,
                                             String requestHash,
                                             long expectedLifecycleVersion,
                                             LifecycleActorType actorType,
                                             String actorId,
                                             String traceId,
                                             String sanitizedRequestContextJson,
                                             Instant requestedAt) {
}
