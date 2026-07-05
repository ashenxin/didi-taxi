package com.sx.wallet.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Accessors(chain = true)
@TableName("wallet_payment_order")
public class WalletPaymentOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String paymentNo;
    private String orderNo;
    private Long passengerId;
    private String channel;
    private Long agreementId;
    private BigDecimal amount;
    private String status;
    private String channelTradeNo;
    private String idempotencyKey;
    private LocalDateTime paidAt;
    private String failedReason;
    private String notifyPayload;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
