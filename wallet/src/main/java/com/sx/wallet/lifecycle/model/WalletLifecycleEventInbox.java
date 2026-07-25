package com.sx.wallet.lifecycle.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Getter @Setter @Accessors(chain = true)
@TableName("wallet_account_lifecycle_event_inbox")
public class WalletLifecycleEventInbox {
    @TableId private String sourceEventId;
    private Long customerId;
    private Long lifecycleVersion;
    private String requestHash;
    private LocalDateTime createdAt;
}
