package com.sx.order.lifecycle.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Getter
@Setter
@Accessors(chain = true)
@TableName("order_account_lifecycle_projection")
public class OrderAccountLifecycleProjection {
    @TableId(type = IdType.INPUT)
    private Long customerId;
    private Integer businessStatus;
    private String lifecycleStatus;
    private Long lifecycleVersion;
    private String operationNo;
    private String sourceEventId;
    private Long rowVersion;
    private LocalDateTime updatedAt;
}
