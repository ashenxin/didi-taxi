package com.sx.passenger.lifecycle.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Getter @Setter @Accessors(chain = true)
@TableName("customer_phone_binding_history")
public class CustomerPhoneBindingHistoryEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private Long customerId;
    private Long bindingVersion;
    private String status;
    private byte[] phoneCiphertext;
    private String phoneIdentityHash;
    private String hashKeyVersion;
    private String changeOperationNo;
    private String changeReason;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    private LocalDateTime retentionUntil;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
