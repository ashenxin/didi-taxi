package com.sx.order.model;

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
@TableName("order_idempotent_record")
public class OrderIdempotentRecord {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String requestId;
    private String actionType;
    private Long passengerId;
    private String orderNo;
    /** PROCESSING/SUCCESS/FAILED */
    private String status;
    private String requestHash;
    /** JSON 字符串（MySQL JSON / H2 VARCHAR） */
    private String responseSnapshot;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
