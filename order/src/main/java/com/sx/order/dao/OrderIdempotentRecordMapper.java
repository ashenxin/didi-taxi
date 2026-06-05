package com.sx.order.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.order.model.OrderIdempotentRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderIdempotentRecordMapper extends BaseMapper<OrderIdempotentRecord> {
}
