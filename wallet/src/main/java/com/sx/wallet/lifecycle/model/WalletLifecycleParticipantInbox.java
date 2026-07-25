package com.sx.wallet.lifecycle.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Getter @Setter @Accessors(chain = true)
@TableName("wallet_lifecycle_participant_inbox")
public class WalletLifecycleParticipantInbox {
    @TableId(type = IdType.AUTO) private Long id;
    private String operationNo;
    private String stepCode;
    private Long customerId;
    private Long lifecycleVersion;
    private String requestHash;
    private String status;
    private String decision;
    private String blockerSnapshot;
    private String resultSnapshot;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
