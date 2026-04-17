-- =============================================================================
-- capacity 库：建表（运力公司 / 司机 / 车辆 / 换队 / 审核流水）
-- 种子数据见 capacity_seed.sql
-- =============================================================================

CREATE DATABASE IF NOT EXISTS `capacity` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE `capacity`;

-- 库：capacity；字段与 com.sx.capacity.model.Company 一致
CREATE TABLE IF NOT EXISTS `company` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `city_code` VARCHAR(32) NULL COMMENT '城市编码',
    `city_name` VARCHAR(64) NULL COMMENT '城市名称',
    `province_code` VARCHAR(32) NULL COMMENT '省份编码',
    `province_name` VARCHAR(64) NULL COMMENT '省份名称',
    `company_no` VARCHAR(32) NOT NULL COMMENT '运力公司编号',
    `company_name` VARCHAR(128) NOT NULL COMMENT '运力公司名称',
    `team_id` BIGINT NULL COMMENT '车队ID',
    `team` VARCHAR(128) NULL COMMENT '车队名称',
    `created_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` INT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_company_team_id` (`team_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='运力';
-- 库：capacity；字段与 com.sx.capacity.model.Driver 一致（运力/车队归 company，本表用 company_id 关联）
CREATE TABLE IF NOT EXISTS `driver` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `driver_source` INT NULL COMMENT '司机来源:0 自行注册 1:导入',
    `city_code` VARCHAR(32) NULL COMMENT '城市编码',
    `city_name` VARCHAR(64) NULL COMMENT '城市名称',
    `province_code` VARCHAR(32) NULL COMMENT '省份编码（国标省码 6 位，与前端级联一致）',
    `province_name` VARCHAR(64) NULL COMMENT '省份名称',
    `company_id` BIGINT NULL COMMENT '运力主体ID，关联 company.id',
    `brand_no` VARCHAR(32) NULL COMMENT '品牌编号',
    `brand_name` VARCHAR(128) NULL COMMENT '品牌名称',
    `name` VARCHAR(64) NULL COMMENT '姓名（注册后可补齐；接单前需完善并审核通过）',
    `id_card` VARCHAR(32) NULL COMMENT '身份证（注册后可补齐；接单前需完善并审核通过）',
    `id_card_photo_a` VARCHAR(1024) NULL COMMENT '身份证照片正面上传到oss的链接',
    `id_card_photo_b` VARCHAR(1024) NULL COMMENT '身份证照片反面上传到oss的链接',
    `phone` VARCHAR(32) NOT NULL COMMENT '手机号码',
    `password_hash` VARCHAR(128) NULL COMMENT '密码摘要（BCrypt）；为空表示未设置密码',
    `gender` INT NULL COMMENT '性别（0：未知的性别，1：男性，2：女性）',
    `birthday` DATE NULL COMMENT '出生日期',
    `nationality` VARCHAR(64) NULL COMMENT '国籍',
    `nation` VARCHAR(32) NULL COMMENT '民族',
    `marital_status` INT NULL COMMENT '婚姻状况（0未婚，1已婚，2离异）',
    `photo_oss` VARCHAR(1024) NULL COMMENT '驾驶员照片上传到oss的链接',
    `with_car_photo` VARCHAR(1024) NULL COMMENT '人车合照上传到oss的链接',
    `license_photo_oss_a` VARCHAR(1024) NULL COMMENT '驾驶证正页照片上传到oss的链接',
    `license_photo_oss_b` VARCHAR(1024) NULL COMMENT '驾驶证副页照片上传到oss的链接',
    `get_driver_license_date` DATE NULL COMMENT '初次获取驾驶证日期',
    `driver_license_on` DATE NULL COMMENT '驾驶证有效期起',
    `driver_license_off` DATE NULL COMMENT '驾驶证有效期止',
    `rpt_status` INT NULL COMMENT '上报账号状态  0-有效，1-失效',
    `monitor_status` INT NULL COMMENT '听单状态：0未听单， 1听单中， 2服务中',
    `can_accept_order` INT NOT NULL DEFAULT 1 COMMENT '是否可接单 0否 1是',
    `audit_status` INT NOT NULL DEFAULT 0 COMMENT '审核状态快照：0待完善 1审核中 2通过 3驳回/需补件',
    `audit_last_record_id` BIGINT NULL COMMENT '最新审核流水ID（driver_audit_record.id）',
    `created_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` INT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否',
    `phone_if_active` VARCHAR(32) GENERATED ALWAYS AS (CASE WHEN `is_deleted` = 0 THEN `phone` ELSE NULL END) STORED COMMENT '仅未删除行参与唯一；删除行为 NULL',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_driver_phone_active` (`phone_if_active`),
    KEY `idx_driver_company_id` (`company_id`),
    KEY `idx_driver_province_code` (`province_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='司机';
-- 库：capacity；字段与 com.sx.capacity.model.Car 一致
CREATE TABLE IF NOT EXISTS `car` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `driver_id` BIGINT NULL COMMENT '绑定司机ID，关联 driver.id',
    `brand_no` VARCHAR(32) NULL COMMENT '品牌编号',
    `brand_name` VARCHAR(128) NULL COMMENT '品牌名称',
    `city_code` VARCHAR(32) NOT NULL COMMENT '城市ID',
    `city_name` VARCHAR(64) NULL COMMENT '城市名称',
    `car_no` VARCHAR(32) NOT NULL COMMENT '车牌号',
    `plate_color` VARCHAR(32) NULL COMMENT '车牌颜色',
    `vehicle_type` VARCHAR(64) NULL COMMENT '行驶证上的车辆类型',
    `owner_name` VARCHAR(64) NULL COMMENT '行驶证上的车辆所有人',
    `certify_date_a` DATE NULL COMMENT '车辆注册时间',
    `fuel_type` VARCHAR(32) NULL COMMENT '车辆燃料类型',
    `photo_oss` VARCHAR(1024) NULL COMMENT '上传的车辆图片地址',
    `with_photo_oss` VARCHAR(1024) NULL COMMENT '人车合影（照片）',
    `ride_type_id` VARCHAR(64) NOT NULL COMMENT '运力类型：经济型，舒适型(ride_type_id)',
    `business_type_id` VARCHAR(64) NULL COMMENT '业务类型：专车，快车(business_type_id)',
    `car_state` INT NULL COMMENT '状态；0：有效，1：失效',
    `car_num` INT NULL COMMENT '汽车座位数',
    `created_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` INT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否',
    PRIMARY KEY (`id`),
    KEY `idx_car_driver_id` (`driver_id`),
    UNIQUE KEY `uk_car_driver_id` (`driver_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='车辆';
-- 字段须与 com.sx.capacity.model.DriverTeamChangeRequest 一致
CREATE TABLE IF NOT EXISTS `driver_team_change_request` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `driver_id` BIGINT NOT NULL COMMENT '司机ID',
    `from_company_id` BIGINT NULL COMMENT '申请前所属运力主体(company.id)',
    `to_company_id` BIGINT NOT NULL COMMENT '目标运力主体(company.id)',
    `status` VARCHAR(32) NOT NULL COMMENT 'PENDING/APPROVED/REJECTED/CANCELLED',
    `request_reason` VARCHAR(512) NULL COMMENT '司机申请说明',
    `requested_by` VARCHAR(64) NULL COMMENT '申请人标识',
    `requested_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    `reviewed_by` VARCHAR(64) NULL COMMENT '审核人',
    `reviewed_at` DATETIME NULL COMMENT '审核时间',
    `review_reason` VARCHAR(512) NULL COMMENT '审核备注/拒绝原因',
    `created_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` INT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否',
    PRIMARY KEY (`id`),
    KEY `idx_dtcr_driver` (`driver_id`),
    KEY `idx_dtcr_status` (`status`),
    KEY `idx_dtcr_requested_at` (`requested_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='司机换队申请';
-- 库：capacity；司机资料审核流水表（每次审核插入一条记录）
CREATE TABLE IF NOT EXISTS `driver_audit_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `driver_id` BIGINT NOT NULL COMMENT '司机ID，关联 driver.id',
    `result_status` INT NOT NULL COMMENT '审核结果状态（与 driver.audit_status 口径一致或更细）',
    `reason` VARCHAR(2000) NULL COMMENT '驳回原因/补件要求（可扩展为 JSON）',
    `operator_type` INT NOT NULL COMMENT '操作人类型：0系统 1运营 2客服 3审核员等',
    `operator_id` BIGINT NULL COMMENT '操作人ID（可空）',
    `submission_id` BIGINT NULL COMMENT '资料提交批次/版本ID（可空，建议后续补齐）',
    `created_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_driver_audit_driver` (`driver_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='司机审核流水';

