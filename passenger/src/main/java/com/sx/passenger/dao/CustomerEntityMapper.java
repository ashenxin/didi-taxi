package com.sx.passenger.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.passenger.model.Customer;
import org.springframework.stereotype.Repository;

@Repository
public interface CustomerEntityMapper extends BaseMapper<Customer> {
}
