package com.sx.capacity.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 运力公司（承运主体）实体。
 * <p>对应 MySQL 库 {@code capacity}、表 {@code company}。</p>
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("company")
public class Company {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 城市编码
     */
    private String cityCode;

    /**
     * 城市名称
     */
    private String cityName;

    /**
     * 省份编码（管理端筛选）
     */
    private String provinceCode;

    /**
     * 运力公司编号
     */
    @NotBlank(message = "运力公司编号错误")
    private String companyNo;

    /**
     * 运力公司名称
     */
    @NotBlank(message = "运力公司名称错误")
    private String companyName;

    /**
     * 车队ID
     */
    private Long teamId;

    /**
     * 车队名称
     */
    private String team;

    /**
     * 创建时间
     */
    private Date createdAt;

    /**
     * 更新时间
     */
    private Date updatedAt;

    /**
     * 逻辑删除 0否
     */
    private Integer isDeleted;
}
