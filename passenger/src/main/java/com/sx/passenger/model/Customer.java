package com.sx.passenger.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 乘客账号实体。
 * 对应 MySQL 库 {@code passenger}、表 {@code customer}。
 * 库端若存在生成列 {@code phone_active}（用于唯一约束），勿在本实体映射，避免 INSERT/UPDATE 冲突。
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("customer")
public class Customer {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 手机号，登录主键；未删除时在库端通过 phone_active 唯一
     */
    @NotBlank(message = "手机号不能为空")
    private String phone;

    /**
     * 密码摘要；仅短信登录可无
     */
    private String passwordHash;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 头像地址
     */
    private String avatarUrl;

    /**
     * 账号状态：0 正常，1 冻结等
     */
    @NotNull(message = "账号状态不能为空")
    private Integer status;

    /**
     * 真实姓名，按需实名
     */
    private String realName;

    /**
     * 证件号，敏感信息注意加密/脱敏
     */
    private String idCardNo;

    /**
     * 逻辑删除，0 未删除
     */
    private Integer isDeleted;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
