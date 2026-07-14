package com.sx.calculate.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.calculate.model.BenefitPointsAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BenefitPointsAccountMapper extends BaseMapper<BenefitPointsAccount> {
    @Select("SELECT * FROM benefit_points_account WHERE customer_id = #{customerId} FOR UPDATE")
    BenefitPointsAccount selectByCustomerIdForUpdate(@Param("customerId") Long customerId);
}
