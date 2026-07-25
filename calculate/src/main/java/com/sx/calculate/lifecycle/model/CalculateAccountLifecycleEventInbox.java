package com.sx.calculate.lifecycle.model;

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
@TableName("calculate_account_lifecycle_event_inbox")
public class CalculateAccountLifecycleEventInbox {
    @TableId(type = IdType.INPUT)
    private String sourceEventId;
    private Long customerId;
    private Long lifecycleVersion;
    private String requestHash;
    private LocalDateTime createdAt;
}
