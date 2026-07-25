package com.sx.calculate.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.calculate.model.UserCoupon;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserCouponMapper extends BaseMapper<UserCoupon> {
    @Select("""
            SELECT *
            FROM user_coupon
            WHERE passenger_id = #{passengerId}
              AND status = 'LOCKED'
            ORDER BY id ASC
            LIMIT 20
            FOR UPDATE
            """)
    List<UserCoupon> selectLockedForUpdate(@Param("passengerId") Long passengerId);
}
