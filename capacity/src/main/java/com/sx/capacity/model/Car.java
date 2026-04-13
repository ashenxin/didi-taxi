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
 * 车辆档案实体。
 * 对应 MySQL 库 {@code capacity}、表 {@code car}；列名 snake_case，由 MyBatis-Plus 驼峰映射。
 * 与司机多对一：{@code driver_id} 关联 {@link Driver}{@code .id}（业务上常约束 1 车 1 司机）。
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("car")
public class Car {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 绑定司机ID，关联 driver.id（司机 1 - 1 车辆）。
     */
    private Long driverId;

    /**
     * 品牌编号
     */
    private String brandNo;

    /**
     * 品牌名称
     */
    private String brandName;

    /**
     * 城市ID
     */
    @NotBlank(message = "车辆城市错误")
    private String cityCode;

    /**
     * 城市名称
     */
    private String cityName;

    /** 车牌号 */
    @NotBlank(message = "车牌号信息错误")
    private String carNo;

    /**
     * 车牌颜色
     */
    private String plateColor;

    /**
     * 行驶证上的车辆类型
     */
    private String vehicleType;

    /**
     * 行驶证上的车辆所有人
     */
    private String ownerName;

    /**
     * 车辆注册时间
     */
    private Date certifyDateA;

    /**
     * 车辆燃料类型
     */
    private String fuelType;

    /**
     * 上传的车辆图片地址
     */
    private String photoOss;

    /**
     * 人车合影（照片）
     */
    private String withPhotoOss;

    /**
     * ride_type_id(运力类型：经济型，舒适型)
     */
    @NotBlank(message = "运力类型或车型错误")
    private String rideTypeId;

    /**
     * 业务类型id(专车，快车)
     */
    private String businessTypeId;

    /**
     * 状态；0：有效，1：失效
     */
    private Integer carState;

    /**
     * 汽车座位数
     */
    private Integer carNum;

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
