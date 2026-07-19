package com.sx.calculate.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.calculate.model.BenefitSignRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface BenefitSignRecordMapper extends BaseMapper<BenefitSignRecord> {
    @Select("SELECT DISTINCT customer_id FROM benefit_sign_record "
            + "WHERE sign_year_month = #{yearMonth} AND customer_id > #{cursor} "
            + "ORDER BY customer_id LIMIT #{limit}")
    List<Long> selectCustomerIdsByMonthAfter(@Param("yearMonth") String yearMonth,
                                              @Param("cursor") long cursor,
                                              @Param("limit") int limit);

    @Select("SELECT DISTINCT customer_id FROM benefit_sign_record "
            + "WHERE updated_at >= #{since} AND customer_id > #{cursor} "
            + "ORDER BY customer_id LIMIT #{limit}")
    List<Long> selectCustomerIdsUpdatedAfter(@Param("since") LocalDateTime since,
                                              @Param("cursor") long cursor,
                                              @Param("limit") int limit);

    @Select("SELECT DISTINCT customer_id FROM benefit_sign_record "
            + "WHERE customer_id > #{cursor} ORDER BY customer_id LIMIT #{limit}")
    List<Long> selectAllCustomerIdsAfter(@Param("cursor") long cursor, @Param("limit") int limit);
}
