package com.sx.passenger.lifecycle.application;

import com.sx.passenger.lifecycle.domain.LifecycleActorType;
import com.sx.passenger.lifecycle.domain.LifecycleOperationStatus;

import java.time.Instant;

/**
 * 请求一次 Operation 状态迁移的审计命令。
 *
 * @param expectedRowVersion 调用方读取到的乐观锁版本
 * @param targetStatus 目标状态
 * @param reasonCode 稳定原因码
 * @param sanitizedPayloadJson 已脱敏审计载荷
 */
public record TransitionLifecycleOperationCommand(long operationId,
                                                  long expectedRowVersion,
                                                  LifecycleOperationStatus targetStatus,
                                                  LifecycleActorType actorType,
                                                  String actorId,
                                                  String reasonCode,
                                                  String traceId,
                                                  String sanitizedPayloadJson,
                                                  Instant occurredAt) {
}
