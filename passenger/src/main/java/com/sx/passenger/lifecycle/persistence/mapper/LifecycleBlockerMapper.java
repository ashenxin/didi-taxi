package com.sx.passenger.lifecycle.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleBlockerEntity;

/** 阻断项基础持久化入口；复杂条件由编排事务使用 MyBatis-Plus 构造。 */
public interface LifecycleBlockerMapper extends BaseMapper<LifecycleBlockerEntity> {}
