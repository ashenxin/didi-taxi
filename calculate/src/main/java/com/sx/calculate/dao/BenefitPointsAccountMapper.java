package com.sx.calculate.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.calculate.model.BenefitPointsAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface BenefitPointsAccountMapper extends BaseMapper<BenefitPointsAccount> {
    @Select("SELECT * FROM benefit_points_account WHERE customer_id = #{customerId} FOR UPDATE")
    BenefitPointsAccount selectByCustomerIdForUpdate(@Param("customerId") Long customerId);

    @Select("SELECT customer_id FROM benefit_points_account WHERE updated_at >= #{since} "
            + "AND customer_id > #{cursor} ORDER BY customer_id LIMIT #{limit}")
    List<Long> selectCustomerIdsUpdatedAfter(@Param("since") LocalDateTime since,
                                              @Param("cursor") long cursor,
                                              @Param("limit") int limit);

    @Select("SELECT customer_id FROM benefit_points_account WHERE customer_id > #{cursor} "
            + "ORDER BY customer_id LIMIT #{limit}")
    List<Long> selectAllCustomerIdsAfter(@Param("cursor") long cursor, @Param("limit") int limit);
}
