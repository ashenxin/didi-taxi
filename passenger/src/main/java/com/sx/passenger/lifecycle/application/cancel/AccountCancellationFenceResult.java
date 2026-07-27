package com.sx.passenger.lifecycle.application.cancel;

/**
 * 注销栅栏建立或幂等重放后的结果。
 *
 * @param operationNo 后续受限会话必须绑定的 Operation 编号
 * @param appliedLifecycleVersion 栅栏成功写入后的账号生命周期版本
 * @param restrictedAuthEpoch 受限 Token 必须携带的认证代次
 */
public record AccountCancellationFenceResult(long operationId,
                                             String operationNo,
                                             long customerId,
                                             long appliedLifecycleVersion,
                                             long restrictedAuthEpoch,
                                             String status) {
}
