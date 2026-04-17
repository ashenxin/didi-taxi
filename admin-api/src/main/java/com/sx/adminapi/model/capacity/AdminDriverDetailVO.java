package com.sx.adminapi.model.capacity;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 管理端司机详情：与 capacity {@code driver} 表可读字段对齐；不包含 {@code passwordHash}。
 * 另可附带 {@link #companyName}（由 BFF 根据 {@code companyId} 查公司补充）。
 */
@Getter
@Setter
public class AdminDriverDetailVO {

    private Long id;
    private Integer driverSource;

    @JsonAlias({ "city_code" })
    private String cityCode;
    @JsonAlias({ "city_name" })
    private String cityName;
    @JsonAlias({ "province_code" })
    private String provinceCode;
    @JsonAlias({ "province_name" })
    private String provinceName;

    private Long companyId;
    /** 归属运力公司名称（BFF 扩展） */
    private String companyName;

    private String brandNo;
    private String brandName;

    private String name;
    private String idCard;
    @JsonAlias({ "id_card_photo_a" })
    private String idCardPhotoA;
    @JsonAlias({ "id_card_photo_b" })
    private String idCardPhotoB;

    private String phone;

    private Integer gender;
    private Date birthday;
    private String nationality;
    private String nation;
    private Integer maritalStatus;

    @JsonAlias({ "photo_oss" })
    private String photoOss;
    @JsonAlias({ "with_car_photo" })
    private String withCarPhoto;
    @JsonAlias({ "license_photo_oss_a" })
    private String licensePhotoOssA;
    @JsonAlias({ "license_photo_oss_b" })
    private String licensePhotoOssB;

    @JsonAlias({ "get_driver_license_date" })
    private Date getDriverLicenseDate;
    @JsonAlias({ "driver_license_on" })
    private Date driverLicenseOn;
    @JsonAlias({ "driver_license_off" })
    private Date driverLicenseOff;

    @JsonAlias({ "rpt_status" })
    private Integer rptStatus;
    @JsonAlias({ "monitor_status" })
    private Integer monitorStatus;
    @JsonAlias({ "can_accept_order" })
    private Integer canAcceptOrder;
    @JsonAlias({ "audit_status" })
    private Integer auditStatus;
    @JsonAlias({ "audit_last_record_id" })
    private Long auditLastRecordId;

    @JsonAlias({ "created_at" })
    private Date createdAt;
    @JsonAlias({ "updated_at" })
    private Date updatedAt;
}
