package com.sx.passenger.lifecycle.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOutboxEntity;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** Outbox 候选扫描和多实例竞争领取 Mapper。 */
public interface LifecycleOutboxMapper extends BaseMapper<LifecycleOutboxEntity> {
    /** 查找已到重试时间的记录及超过 staleBefore 的陈旧 PROCESSING 记录。 */
    List<LifecycleOutboxEntity> findPublishCandidates(
            @Param("now") LocalDateTime now,
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("limit") int limit);

    /** 原子领取候选记录；返回 0 表示已经被其他发布实例抢占。 */
    int claim(@Param("id") long id,
              @Param("now") LocalDateTime now,
              @Param("staleBefore") LocalDateTime staleBefore,
              @Param("worker") String worker);
}
