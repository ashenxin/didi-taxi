package com.sx.passenger.lifecycle.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleEventEntity;

/** 生命周期审计事件持久化入口；事件记录原则上只新增、不回写。 */
public interface LifecycleEventMapper extends BaseMapper<LifecycleEventEntity> {}
