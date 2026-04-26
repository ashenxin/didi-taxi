package com.sx.order.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.order.model.OrderOutboxEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderOutboxEventMapper extends BaseMapper<OrderOutboxEvent> {
}

