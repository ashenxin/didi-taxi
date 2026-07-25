package com.sx.passenger.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.passenger.model.Customer;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface CustomerEntityMapper extends BaseMapper<Customer> {
    Long selectAuthEpochById(@Param("customerId") long customerId);

    int bumpAuthEpochForAuthentication(@Param("customerId") long customerId);

    int bumpAuthEpochForLogout(@Param("customerId") long customerId,
                               @Param("expectedAuthEpoch") long expectedAuthEpoch);

    int fenceAccountCancellation(@Param("customerId") long customerId,
                                 @Param("expectedLifecycleVersion") long expectedLifecycleVersion,
                                 @Param("operationNo") String operationNo,
                                 @Param("updatedAt") LocalDateTime updatedAt);

    int changePhoneCas(@Param("customerId") long customerId,
                       @Param("newPhone") String newPhone,
                       @Param("expectedLifecycleVersion") long expectedLifecycleVersion);

    int cancelAccountCas(@Param("customerId") long customerId,
                         @Param("expectedLifecycleVersion") long expectedLifecycleVersion,
                         @Param("cancelledAt") LocalDateTime cancelledAt);

    int finalizeAccountCancellation(@Param("customerId") long customerId,
                                    @Param("operationNo") String operationNo,
                                    @Param("expectedLifecycleVersion") long expectedLifecycleVersion,
                                    @Param("cancelledAt") LocalDateTime cancelledAt);
}
