package com.sx.passenger.lifecycle.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** Operation 的加锁读取、恢复扫描和乐观锁状态迁移 Mapper。 */
public interface LifecycleOperationMapper extends BaseMapper<LifecycleOperationEntity> {
    /** 按业务编号加行锁读取，供单事务内编排与状态迁移。 */
    LifecycleOperationEntity findByOperationNoForUpdate(@Param("operationNo") String operationNo);

    /** 扫描已达到 next_wakeup_at 的待恢复 Operation。 */
    List<LifecycleOperationEntity> findDueForRecovery(
            @Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 在注销栅栏建立后回填 Operation 绑定的受限认证代次。 */
    int updateRestrictedAuthEpoch(@Param("customerId") long customerId,
                                  @Param("operationNo") String operationNo,
                                  @Param("restrictedAuthEpoch") long restrictedAuthEpoch,
                                  @Param("updatedAt") LocalDateTime updatedAt);

    /** 同时匹配当前状态和 rowVersion 的通用 CAS 状态更新。 */
    int updateStatusCas(@Param("id") long id,
                        @Param("expectedStatus") String expectedStatus,
                        @Param("expectedRowVersion") long expectedRowVersion,
                        @Param("targetStatus") String targetStatus,
                        @Param("updatedAt") LocalDateTime updatedAt);

    /** 将 REQUESTED 注销操作原子迁移到 FENCED 并记录版本。 */
    int fenceRequestedCas(@Param("id") long id,
                          @Param("expectedRowVersion") long expectedRowVersion,
                          @Param("restrictedAuthEpoch") long restrictedAuthEpoch,
                          @Param("appliedLifecycleVersion") long appliedLifecycleVersion,
                          @Param("fencedAt") LocalDateTime fencedAt);

    /** 将换号 Operation 从 REQUESTED 原子迁移到 EXECUTING。 */
    int startPhoneChangeCas(@Param("id") long id,
                            @Param("expectedRowVersion") long expectedRowVersion,
                            @Param("startedAt") LocalDateTime startedAt);

    /** 手机号提交成功后将换号 Operation 原子标记为 COMPLETED。 */
    int completePhoneChangeCas(@Param("id") long id,
                               @Param("expectedRowVersion") long expectedRowVersion,
                               @Param("appliedLifecycleVersion") long appliedLifecycleVersion,
                               @Param("newAuthEpoch") long newAuthEpoch,
                               @Param("completedAt") LocalDateTime completedAt);

    /** 不可逆开始前把 FENCED/BLOCKED 注销操作标记为 ABORTED。 */
    int abortCancellationCas(@Param("id") long id,
                             @Param("expectedStatus") String expectedStatus,
                             @Param("expectedRowVersion") long expectedRowVersion,
                             @Param("appliedLifecycleVersion") long appliedLifecycleVersion,
                             @Param("authEpoch") long authEpoch,
                             @Param("abortedAt") LocalDateTime abortedAt);

    /** 用户解阻后把 BLOCKED 操作重新置为 VALIDATING。 */
    int recheckBlockedCas(@Param("id") long id,
                          @Param("expectedRowVersion") long expectedRowVersion,
                          @Param("updatedAt") LocalDateTime updatedAt);
}
