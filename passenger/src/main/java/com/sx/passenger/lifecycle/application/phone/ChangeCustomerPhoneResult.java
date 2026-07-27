package com.sx.passenger.lifecycle.application.phone;

/**
 * 换号事务成功或幂等重放后的结果。
 *
 * @param appliedLifecycleVersion 换号后账号生命周期版本
 * @param authEpoch 换号后认证代次，旧 Token 应立即失效
 * @param completed 是否已完整完成
 */
public record ChangeCustomerPhoneResult(long operationId,
                                        String operationNo,
                                        long customerId,
                                        long appliedLifecycleVersion,
                                        long newAuthEpoch,
                                        boolean requireLogin) {
}
