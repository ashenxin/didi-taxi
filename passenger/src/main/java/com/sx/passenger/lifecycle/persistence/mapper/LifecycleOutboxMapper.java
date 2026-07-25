package com.sx.passenger.lifecycle.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOutboxEntity;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface LifecycleOutboxMapper extends BaseMapper<LifecycleOutboxEntity> {
    List<LifecycleOutboxEntity> findPublishCandidates(
            @Param("now") LocalDateTime now,
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("limit") int limit);

    int claim(@Param("id") long id,
              @Param("now") LocalDateTime now,
              @Param("staleBefore") LocalDateTime staleBefore,
              @Param("worker") String worker);
}
