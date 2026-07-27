package com.sx.passenger.lifecycle.orchestration;

import java.time.LocalDateTime;

/**
 * passenger 编排器发送给业务参与者的标准命令。
 *
 * @param eventId 命令幂等事件 ID
 * @param operationNo 生命周期 Operation 编号
 * @param stepCode 当前步骤
 * @param lifecycleVersion 参与者应应用的账号生命周期版本
 * @param targetLifecycleStatus 期望参与者投影状态
 * @param targetDomain 目标业务域
 */
public record LifecycleParticipantCommand(
        String eventId,
        String operationNo,
        String stepCode,
        long customerId,
        long lifecycleVersion,
        String targetLifecycleStatus,
        String targetDomain,
        LocalDateTime requestedAt) {
}
