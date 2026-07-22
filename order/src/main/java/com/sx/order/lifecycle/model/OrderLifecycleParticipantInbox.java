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
@TableName("order_lifecycle_participant_inbox")
public class OrderLifecycleParticipantInbox {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String operationNo;
    private String stepCode;
    private Long customerId;
    private String requestHash;
    private String status;
    private String decision;
    private String blockerSnapshot;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
