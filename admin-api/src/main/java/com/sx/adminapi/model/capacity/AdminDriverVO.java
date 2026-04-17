package com.sx.adminapi.model.capacity;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class AdminDriverVO {
    private Long id;
    private Integer driverSource;
    @JsonAlias({ "city_code" })
    private String cityCode;
    @JsonAlias({ "city_name" })
    private String cityName;
    /** 省份编码（与前端行政区划一致；列表省/市列展示与筛选） */
    @JsonAlias({ "province_code" })
    private String provinceCode;
    /** 省份名称 */
    @JsonAlias({ "province_name" })
    private String provinceName;
    private Long companyId;
    private String brandNo;
    private String brandName;
    private String name;
    private String idCard;
    private String phone;
    private Integer gender;
    private Integer rptStatus;
    private Integer monitorStatus;
    /** 是否可接单：0 否，1 是 */
    private Integer canAcceptOrder;
    /** 审核状态：0待完善 1审核中 2通过 3驳回/需补件 */
    private Integer auditStatus;
    private Date createdAt;
    private Date updatedAt;
}

