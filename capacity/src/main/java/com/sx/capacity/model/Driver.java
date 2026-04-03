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
 * 司机档案实体。
 * <p>对应 MySQL 库 {@code capacity}、表 {@code driver}（列名 snake_case，由 MyBatis-Plus 驼峰映射）。</p>
 * <p>运力公司见 {@link Company}（字段 {@code companyId}）；车辆见 {@link Car}（其 {@code driverId} 指向本司机）。</p>
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("driver")
public class Driver {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 司机来源:0 自行注册 1:导入
     */
    private Integer driverSource;

    /**
     * 城市编码
     */
    private String cityCode;

    /**
     * 城市名称
     */
    private String cityName;

    /**
     * 运力主体ID，关联 company.id
     */
    private Long companyId;

    /**
     * 品牌编号
     */
    private String brandNo;

    /**
     * 品牌名称
     */
    private String brandName;

    /**
     * 姓名
     */
    @NotBlank(message = "司机姓名错误")
    private String name;

    /**
     * 身份证
     */
    @NotBlank(message = "司机身份证错误")
    private String idCard;

    /**
     * 身份证照片正面上传到oss的链接
     */
    private String idCardPhotoA;

    /**
     * 身份证照片反面上传到oss的链接
     */
    private String idCardPhotoB;

    /**
     * 手机号码
     */
    @NotBlank(message = "司机手机号错误")
    private String phone;

    /**
     * 性别（0：未知的性别，1：男性，2：女性）
     */
    private Integer gender;

    /**
     * 出生日期
     */
    private Date birthday;

    /**
     * 国籍
     */
    private String nationality;

    /**
     * 民族
     */
    private String nation;

    /**
     * 婚姻状况（0未婚，1已婚，2离异）
     */
    private Integer maritalStatus;

    /**
     * 驾驶员照片上传到oss的链接
     */
    private String photoOss;

    /**
     * 人车合照上传到oss的链接
     */
    private String withCarPhoto;

    /**
     * 驾驶证正页照片上传到oss的链接
     */
    private String licensePhotoOssA;

    /**
     * 驾驶证副页照片上传到oss的链接
     */
    private String licensePhotoOssB;

    /**
     * 初次获取驾驶证日期
     */
    private Date getDriverLicenseDate;

    /**
     * 驾驶证有效期起
     */
    private Date driverLicenseOn;

    /**
     * 驾驶证有效期止
     */
    private Date driverLicenseOff;

    /**
     * 上报账号状态  0-有效，1-失效
     */
    private Integer rptStatus;

    /**
     * 听单状态：0未听单， 1听单中， 2服务中
     */
    private Integer monitorStatus;

    /**
     * 是否可接单：0 否，1 是（换队审核中等场景为 0）
     */
    private Integer canAcceptOrder;

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
