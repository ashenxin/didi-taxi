package com.sx.wallet.model;

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
@TableName("wallet_auto_pay_agreement")
public class WalletAutoPayAgreement {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long passengerId;
    private String channel;
    private String channelUserId;
    private String agreementNo;
    private String agreementStatus;
    private Integer isDefault;
    private String signScene;
    private LocalDateTime signedAt;
    private LocalDateTime closedAt;
    private LocalDateTime lastUsedAt;
    private String failReason;
    private String rawRequest;
    private String rawResponse;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer isDeleted;
}
