-- =============================================================================
-- 司机端行程记录：MySQL 8 存量库新增表（由用户手动执行）
-- 前置：已有 order.trip_order；执行时间：新代码承接接单写入之前。
-- 金额权威仍为 trip_order_settlement，本表不重复保存最终账单与司机收入。
-- 建表可重复执行；同名表若已存在，不会自动变更其结构或注释。
-- 先建表，再在暂停订单写流量的维护窗口执行独立历史回填脚本。
-- =============================================================================
USE `order`;
CREATE TABLE IF NOT EXISTS driver_trip_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '司机服务记录ID；前端作为tripId字符串使用',
    order_id BIGINT NOT NULL COMMENT '订单主键；关联trip_order.id',
    order_no VARCHAR(64) NOT NULL COMMENT '乘客订单号；同一订单可对应多名司机或多次服务',
    source_key VARCHAR(128) NOT NULL COMMENT '创建来源唯一键；event:接单事件ID或legacy存量补录标识',
    driver_id BIGINT NOT NULL COMMENT '本次成功接单司机ID；改派后保留原值',
    car_id BIGINT NULL COMMENT '本次服务车辆ID快照；历史不可证明时为空',
    company_id BIGINT NULL COMMENT '本次承运公司或车队ID快照；不跟随司机后续换队',
    city_code VARCHAR(32) NULL COMMENT '服务城市行政区划编码',
    product_code VARCHAR(64) NULL COMMENT '服务产品编码，如ECONOMY',
    origin_address VARCHAR(255) NULL COMMENT '本次订单上车点地址快照',
    dest_address VARCHAR(255) NULL COMMENT '本次订单目的地地址快照',
    status INT NOT NULL COMMENT '司机服务状态：2已接单、3已到达、4行程中、5已完成、6已取消',
    active_flag TINYINT NULL COMMENT '活动服务为1，完成或取消为NULL；配合唯一约束限制同单一条活动服务',
    estimated_amount DECIMAL(10,2) NULL COMMENT '接单时订单预估车费，单位元；不得作为最终车费参与运营金额汇总',
    distance_meters BIGINT NULL COMMENT '下单冻结路线里程，单位米；当前为Mock数据，未知为NULL',
    distance_source VARCHAR(32) NULL COMMENT '里程来源，如LOCAL_MOCK_ROUTE；未知为NULL',
    ordered_at DATETIME NULL COMMENT '乘客下单时间，按Asia/Shanghai本地时间保存',
    accepted_at DATETIME NOT NULL COMMENT '本次司机成功接单时间；行程列表默认排序与日期筛选依据',
    arrived_at DATETIME NULL COMMENT '本次司机到达上车点时间；未发生或历史缺失为NULL',
    started_at DATETIME NULL COMMENT '本次服务开始时间；未发生或历史缺失为NULL',
    finished_at DATETIME NULL COMMENT '本次完单时间；今日完成数及金额按此归属自然日',
    cancelled_at DATETIME NULL COMMENT '本次司机服务取消时间；不是改派后订单最终取消时间',
    service_duration_seconds BIGINT NULL COMMENT '开始至完单的非负时长，单位秒；不等于Mock计费时长',
    cancel_by INT NULL COMMENT '本次服务取消方：1乘客、2司机含登出释单、3系统；未知为NULL',
    cancel_reason VARCHAR(255) NULL COMMENT '本次取消原因码或描述；不读取改派后其他司机的原因',
    updated_at DATETIME NOT NULL COMMENT '记录最后更新时间，按Asia/Shanghai本地时间保存',
    -- 同一接单事件或同一存量来源只允许生成一次记录，防重放/重复回填。
    CONSTRAINT uk_driver_trip_source UNIQUE (source_key),
    -- MySQL允许多条NULL；终态可以并存，同一订单最多一条active_flag=1。
    CONSTRAINT uk_driver_trip_active UNIQUE (order_no, active_flag),
    -- 仅物理删除订单时级联删除；正常逻辑删除和账号注销不删除历史记录。
    CONSTRAINT fk_driver_trip_order FOREIGN KEY (order_id) REFERENCES trip_order(id) ON DELETE CASCADE,
    -- 司机行程列表：司机过滤、接单时间排序、ID稳定打破同秒排序。
    KEY idx_driver_trip_accepted (driver_id, accepted_at, id),
    -- 今日完成/金额/里程/时长按完单时间聚合与明细跳转。
    KEY idx_driver_trip_finished (driver_id, status, finished_at, id),
    -- 今日取消按本次取消时间聚合，支持已取消明细查询。
    KEY idx_driver_trip_cancelled (driver_id, status, cancelled_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='司机每次接单服务历史';
