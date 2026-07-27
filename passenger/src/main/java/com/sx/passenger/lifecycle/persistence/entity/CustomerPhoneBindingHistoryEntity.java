package com.sx.passenger.lifecycle.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * customer 与手机号绑定关系的历史记录。
 *
 * <p>换号不会改变 customerId。本表记录每一代手机号何时生效、何时被替换或释放，
 * 并关联触发变化的生命周期 Operation，用于审计和号码重新绑定风险判断。
 */
@Getter @Setter @Accessors(chain = true)
@TableName("customer_phone_binding_history")
public class CustomerPhoneBindingHistoryEntity {
    /** 数据库主键。 */
    @TableId(type = IdType.AUTO) private Long id;
    /** 稳定的乘客账号标识。 */
    private Long customerId;
    /** 该 customer 的手机号绑定代次，换号时单调递增。 */
    private Long bindingVersion;
    /** ACTIVE、REPLACED 或 RELEASED。 */
    private String status;
    /** 手机号密文及用于等值识别的不可逆摘要。 */
    private byte[] phoneCiphertext;
    private String phoneIdentityHash;
    /** 生成摘要时使用的密钥版本。 */
    private String hashKeyVersion;
    /** 导致本条绑定变化的生命周期业务编号和原因。 */
    private String changeOperationNo;
    private String changeReason;
    /** 绑定有效时间窗口；ACTIVE 记录的 validTo 为空。 */
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    /** 合规保留截止时间。 */
    private LocalDateTime retentionUntil;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
