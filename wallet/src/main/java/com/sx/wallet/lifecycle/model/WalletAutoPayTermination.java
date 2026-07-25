package com.sx.wallet.lifecycle.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Getter @Setter @Accessors(chain = true)
@TableName("wallet_auto_pay_termination")
public class WalletAutoPayTermination {
    @TableId(type = IdType.AUTO) private Long id;
    private String operationNo;
    private String stepCode;
    private Long customerId;
    private Long agreementId;
    private String channel;
    private String agreementNoSnapshot;
    private String status;
    private String channelRequestNo;
    private String channelResponseSnapshot;
    private String manualActor;
    private String manualReason;
    private String manualEvidence;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
