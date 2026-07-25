package com.sx.calculate.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.calculate.model.BenefitPointsAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface BenefitPointsAccountMapper extends BaseMapper<BenefitPointsAccount> {
    @Select("SELECT * FROM benefit_points_account WHERE customer_id = #{customerId} FOR UPDATE")
    BenefitPointsAccount selectByCustomerIdForUpdate(@Param("customerId") Long customerId);

    /**
     * CAS 更新积分账户，防止并发覆盖。仅当 version 匹配时才执行更新并自增 version。
     *
     * @return 受影响行数（1=成功，0=版本冲突）
     */
    @Update("UPDATE benefit_points_account SET available_points = #{account.availablePoints}, "
            + "total_earned_points = #{account.totalEarnedPoints}, "
            + "total_used_points = #{account.totalUsedPoints}, "
            + "total_cleared_points = #{account.totalClearedPoints}, "
            + "status = #{account.status}, last_sign_date = #{account.lastSignDate}, "
            + "last_points_flow_id = #{account.lastPointsFlowId}, "
            + "version = #{account.version}, updated_at = #{account.updatedAt} "
            + "WHERE id = #{account.id} AND version = #{expectedVersion}")
    int updateWithVersion(@Param("account") BenefitPointsAccount account,
                          @Param("expectedVersion") int expectedVersion);

    @Select("SELECT customer_id FROM benefit_points_account WHERE updated_at >= #{since} "
            + "AND customer_id > #{cursor} ORDER BY customer_id LIMIT #{limit}")
    List<Long> selectCustomerIdsUpdatedAfter(@Param("since") LocalDateTime since,
                                              @Param("cursor") long cursor,
                                              @Param("limit") int limit);

    @Select("SELECT customer_id FROM benefit_points_account WHERE customer_id > #{cursor} "
            + "ORDER BY customer_id LIMIT #{limit}")
    List<Long> selectAllCustomerIdsAfter(@Param("cursor") long cursor, @Param("limit") int limit);
}
