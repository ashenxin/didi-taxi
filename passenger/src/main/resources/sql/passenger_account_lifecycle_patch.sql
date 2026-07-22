-- =============================================================================
-- passenger 库：乘客账号换号 / 注销统一生命周期中心增量 DDL（设计评审稿）
--
-- 目标：
-- 1. passenger 成为账号生命周期、认证 epoch（认证代次） 和流程进度的权威来源。
-- 2. 通过 operation / step / blocker（阻塞器） / event / outbox（发件箱） 持久化注销 Saga。
-- 3. 流程模板由随应用发布的版本化 YAML 定义；数据库只保存计划摘要和运行步骤快照。
-- 4. 换号只变更登录凭据，不迁移以 customer.id 归属的订单和资产。
--
-- 注意：
-- - 本文件是一次性增量补丁，不要对已经执行成功的库重复运行。
-- - 当前仅定义 passenger 生命周期控制面的表；order / wallet / calculate 的
--   participant inbox、领域动作流水和查询接口应在各自数据库补丁中定义。
-- - 不在本补丁中回填历史手机号密文/HMAC；回填必须由持有加密与 HMAC 密钥的
--   应用任务执行，禁止把明文手机号复制到审计 JSON 或 Outbox payload。
-- =============================================================================

USE `passenger`;

-- -----------------------------------------------------------------------------
-- 1. customer：账户业务状态与生命周期状态分离；auth_epoch 改为数据库权威
-- -----------------------------------------------------------------------------
ALTER TABLE `customer`
    ADD COLUMN `lifecycle_status` VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        COMMENT '账户生命周期：ACTIVE正常有效/CANCELLING注销处理中/CANCELLED已注销' AFTER `status`,
    ADD COLUMN `lifecycle_version` BIGINT NOT NULL DEFAULT 0
        COMMENT '生命周期 CAS 版本；换号、注销受理、撤销、完成时递增' AFTER `lifecycle_status`,
    ADD COLUMN `auth_epoch` BIGINT NOT NULL DEFAULT 0
        COMMENT '认证代次的数据库权威；敏感变更后递增，Redis 仅作缓存投影' AFTER `lifecycle_version`,
    ADD COLUMN `current_lifecycle_operation_no` VARCHAR(64) NULL
        COMMENT '当前未结束生命周期操作号；仅用于快速定位，权威进度在 operation 表' AFTER `auth_epoch`,
    ADD COLUMN `cancelled_at` DATETIME NULL
        COMMENT '最终完成账号注销时间；进入 CANCELLING 时不得提前填写' AFTER `current_lifecycle_operation_no`,
    ADD KEY `idx_customer_lifecycle_status` (`lifecycle_status`, `updated_at`),
    ADD KEY `idx_customer_current_lifecycle_operation` (`current_lifecycle_operation_no`);

-- -----------------------------------------------------------------------------
-- 2. Operation：一次换号或注销请求的流程聚合根
-- active_customer_id 生成列保证同一乘客最多只有一个未结束生命周期操作。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `account_lifecycle_operation` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `operation_no` VARCHAR(64) NOT NULL COMMENT '对外稳定操作号',
    `customer_id` BIGINT NOT NULL COMMENT '乘客ID，对应 customer.id',
    `operation_type` VARCHAR(32) NOT NULL COMMENT '操作类型：PHONE_CHANGE更换手机号/ACCOUNT_CANCEL注销账号',
    `status` VARCHAR(32) NOT NULL COMMENT '操作状态：REQUESTED已受理/FENCED已建立栅栏/VALIDATING校验中/BLOCKED存在阻断/EXECUTING执行中/RETRY_PENDING等待重试/MANUAL_REVIEW待人工处理/COMPLETED已完成/ABORTED已撤销',
    `idempotency_key` VARCHAR(128) NOT NULL COMMENT '客户端幂等键',
    `request_hash` CHAR(64) NOT NULL COMMENT '规范化请求 SHA-256；同键改内容返回冲突',
    `expected_lifecycle_version` BIGINT NOT NULL COMMENT '客户端提交时看到的账户生命周期版本',
    `applied_lifecycle_version` BIGINT NULL COMMENT '建立栅栏或完成换号后写入的账户版本',
    `plan_code` VARCHAR(64) NOT NULL COMMENT '创建时固化的计划编码',
    `plan_version` INT NOT NULL COMMENT '创建时固化的计划版本',
    `plan_digest` CHAR(64) NOT NULL COMMENT '规范化 YAML 计划的 SHA-256；不保存完整计划快照',
    `irreversible_started` TINYINT NOT NULL DEFAULT 0 COMMENT '不可逆标识：0未开始/1已开始；开始后只允许前向恢复',
    `restricted_auth_epoch` BIGINT NULL COMMENT 'CANCELLING 期间受限生命周期会话使用的认证代次',
    `active_blocker_count` INT NOT NULL DEFAULT 0 COMMENT '当前未解除 blocker 数量的冗余计数',
    `row_version` BIGINT NOT NULL DEFAULT 0 COMMENT 'Operation 状态推进 CAS 版本',
    `next_wakeup_at` DATETIME NULL COMMENT '下次恢复扫描时间',
    `last_error_code` VARCHAR(64) NULL COMMENT '最近流程错误码',
    `last_error_message` VARCHAR(512) NULL COMMENT '最近流程错误摘要，不得包含敏感原文',
    `request_context` JSON NULL COMMENT 'IP/设备/客户端版本等脱敏审计上下文，不得含 OTP/token',
    `requested_at` DATETIME NOT NULL COMMENT '请求时间',
    `fenced_at` DATETIME NULL COMMENT '账户进入 CANCELLING 或换号提交锁定时间',
    `execution_started_at` DATETIME NULL COMMENT '开始不可逆动作时间',
    `completed_at` DATETIME NULL COMMENT '完成时间',
    `aborted_at` DATETIME NULL COMMENT '用户撤销或流程中止时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `active_customer_id` BIGINT GENERATED ALWAYS AS (
        CASE
            WHEN `status` IN ('REQUESTED', 'FENCED', 'VALIDATING', 'BLOCKED', 'EXECUTING', 'RETRY_PENDING', 'MANUAL_REVIEW')
            THEN `customer_id`
            ELSE NULL
        END
    ) STORED COMMENT '未结束流程映射 customer_id；唯一索引防止并行生命周期操作',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_lifecycle_operation_no` (`operation_no`),
    UNIQUE KEY `uk_lifecycle_operation_idempotency` (`customer_id`, `operation_type`, `idempotency_key`),
    UNIQUE KEY `uk_lifecycle_operation_active_customer` (`active_customer_id`),
    KEY `idx_lifecycle_operation_customer_time` (`customer_id`, `created_at`),
    KEY `idx_lifecycle_operation_status_wakeup` (`status`, `next_wakeup_at`, `id`),
    KEY `idx_lifecycle_operation_plan` (`plan_code`, `plan_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号换号/注销生命周期操作';

-- -----------------------------------------------------------------------------
-- 3. Step：Operation 创建时从 YAML 计划复制，之后不受配置文件修改影响
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `account_lifecycle_step` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `operation_id` BIGINT NOT NULL COMMENT 'account_lifecycle_operation.id',
    `step_code` VARCHAR(64) NOT NULL COMMENT '稳定步骤编码',
    `participant_code` VARCHAR(32) NOT NULL COMMENT '参与领域：ORDER订单/WALLET钱包/CALCULATE计价与资产/IDENTITY身份/SESSION会话/ACCOUNT账户',
    `phase` VARCHAR(24) NOT NULL COMMENT '步骤阶段：PRECONDITION前置条件/ACTION业务处理/RETENTION数据保留/POST_ACTION后置动作/FINALIZE最终提交',
    `execution_mode` VARCHAR(24) NOT NULL COMMENT '执行方式：SYNC_CHECK同步检查/ASYNC_COMMAND异步命令/LOCAL_TRANSACTION本地事务',
    `criticality` VARCHAR(24) NOT NULL COMMENT '关键级别：REQUIRED必须成功/POST_ACTION不阻断主流程的后置动作/MANUAL_IF_FAILED失败转人工',
    `sequence_no` INT NOT NULL COMMENT '步骤顺序；同序号可并行',
    `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT '步骤状态：PENDING待执行/RUNNING执行中/SUCCEEDED执行成功/BLOCKED被阻断/RETRY_PENDING等待重试/MANUAL_REVIEW待人工处理/SKIPPED已跳过/CANCELLED已取消',
    `attempt_count` INT NOT NULL DEFAULT 0 COMMENT '实际尝试次数',
    `max_retry_count` INT NOT NULL DEFAULT 0 COMMENT '从计划固化的最大自动重试次数',
    `retry_initial_seconds` INT NOT NULL DEFAULT 5 COMMENT '从计划固化的首次重试间隔',
    `timeout_seconds` INT NOT NULL DEFAULT 30 COMMENT '从计划固化的单次超时',
    `next_retry_at` DATETIME NULL COMMENT '下次自动重试时间',
    `timeout_at` DATETIME NULL COMMENT '本次执行超时时间',
    `command_event_id` VARCHAR(64) NULL COMMENT '最近一次命令事件ID',
    `result_event_id` VARCHAR(64) NULL COMMENT '最近一次结果事件ID',
    `last_error_code` VARCHAR(64) NULL COMMENT '最近步骤错误码',
    `last_error_message` VARCHAR(512) NULL COMMENT '最近步骤错误摘要，不得包含敏感原文',
    `step_config` JSON NULL COMMENT '创建 Operation 时从 YAML 固化的非敏感步骤配置',
    `command_snapshot` JSON NULL COMMENT '命令审计快照，不得包含明文手机号、OTP、token或密钥',
    `result_snapshot` JSON NULL COMMENT '结果审计快照，不得包含渠道敏感原文',
    `started_at` DATETIME NULL,
    `completed_at` DATETIME NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_lifecycle_step_operation_code` (`operation_id`, `step_code`),
    KEY `idx_lifecycle_step_retry` (`status`, `next_retry_at`, `id`),
    KEY `idx_lifecycle_step_participant_status` (`participant_code`, `status`, `updated_at`),
    KEY `idx_lifecycle_step_operation_order` (`operation_id`, `sequence_no`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号生命周期操作步骤';

-- -----------------------------------------------------------------------------
-- 4. Blocker：持久化“为什么不能继续注销”以及用户可执行的解阻动作
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `account_lifecycle_blocker` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `operation_id` BIGINT NOT NULL COMMENT 'account_lifecycle_operation.id',
    `step_id` BIGINT NULL COMMENT '发现该 blocker 的 account_lifecycle_step.id',
    `domain_code` VARCHAR(32) NOT NULL COMMENT '阻断领域：ORDER订单/WALLET钱包/CALCULATE计价与资产/IDENTITY身份等',
    `blocker_key` VARCHAR(191) NOT NULL COMMENT '领域内稳定阻断键，如 UNSETTLED_ORDER:T2026...',
    `blocker_type` VARCHAR(64) NOT NULL COMMENT '结构化阻断类型',
    `resource_type` VARCHAR(64) NULL COMMENT '资源类型：TRIP_ORDER行程订单/PAYMENT支付/REFUND退款/COUPON优惠券等',
    `resource_id` VARCHAR(128) NULL COMMENT '关联业务号；不得使用明文手机号',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '阻断状态：ACTIVE生效中/RESOLVED已解除/WAIVED已人工豁免',
    `resolution_actions` JSON NULL COMMENT '允许用户执行的解阻动作代码列表',
    `snapshot_json` JSON NULL COMMENT '发现时业务快照；金额使用最小货币单位或明确币种，不含敏感原文',
    `detected_at` DATETIME NOT NULL COMMENT '发现时间',
    `last_confirmed_at` DATETIME NOT NULL COMMENT '最近一次复检仍存在的时间',
    `resolved_at` DATETIME NULL COMMENT '解除时间',
    `resolution_reason` VARCHAR(255) NULL COMMENT '解除或人工豁免原因',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_lifecycle_blocker_stable` (`operation_id`, `domain_code`, `blocker_key`),
    KEY `idx_lifecycle_blocker_operation_status` (`operation_id`, `status`, `detected_at`),
    KEY `idx_lifecycle_blocker_resource` (`domain_code`, `resource_type`, `resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号注销结构化阻断项';

-- -----------------------------------------------------------------------------
-- 5. Event：状态迁移、人工操作和关键安全动作的不可覆盖审计流水
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `account_lifecycle_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `event_id` VARCHAR(64) NOT NULL COMMENT '全局唯一事件ID',
    `operation_id` BIGINT NOT NULL COMMENT 'account_lifecycle_operation.id',
    `customer_id` BIGINT NOT NULL COMMENT '乘客ID',
    `event_type` VARCHAR(64) NOT NULL COMMENT '状态迁移或人工动作类型',
    `from_status` VARCHAR(32) NULL COMMENT '变更前状态',
    `to_status` VARCHAR(32) NULL COMMENT '变更后状态',
    `actor_type` VARCHAR(24) NOT NULL COMMENT '操作者类型：CUSTOMER乘客/SYSTEM系统/ADMIN管理员/SERVICE内部服务',
    `actor_id` VARCHAR(64) NULL COMMENT '操作者ID；系统任务可为空',
    `reason_code` VARCHAR(64) NULL COMMENT '稳定原因码',
    `trace_id` VARCHAR(64) NULL COMMENT '全链路 trace/request id',
    `payload_snapshot` JSON NULL COMMENT '脱敏审计快照，不得包含 OTP、token、密码、密钥或渠道敏感原文',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_lifecycle_event_id` (`event_id`),
    KEY `idx_lifecycle_event_operation_time` (`operation_id`, `created_at`, `id`),
    KEY `idx_lifecycle_event_customer_time` (`customer_id`, `created_at`, `id`),
    KEY `idx_lifecycle_event_type_time` (`event_type`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号生命周期审计事件';

-- -----------------------------------------------------------------------------
-- 6. Outbox：生命周期命令与领域事件可靠发布
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `account_lifecycle_outbox` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `event_id` VARCHAR(64) NOT NULL COMMENT '全局唯一事件ID，同时作为消费者去重键',
    `operation_id` BIGINT NOT NULL COMMENT 'account_lifecycle_operation.id',
    `aggregate_type` VARCHAR(32) NOT NULL DEFAULT 'ACCOUNT_LIFECYCLE' COMMENT '聚合类型：ACCOUNT_LIFECYCLE账号生命周期',
    `aggregate_id` VARCHAR(64) NOT NULL COMMENT 'operation_no',
    `event_type` VARCHAR(64) NOT NULL COMMENT '命令或领域事件类型',
    `causation_event_id` VARCHAR(64) NULL COMMENT '触发本消息的 account_lifecycle_event.event_id',
    `trace_id` VARCHAR(64) NULL COMMENT '全链路追踪ID',
    `target_domain` VARCHAR(32) NULL COMMENT '命令目标领域；广播事件可为空',
    `topic` VARCHAR(128) NOT NULL COMMENT 'Kafka topic',
    `partition_key` VARCHAR(128) NOT NULL COMMENT '默认使用 customerId，保证同账号事件有序',
    `payload` JSON NOT NULL COMMENT '事件载荷；只含ID、版本、原因码和时间，不含明文手机号',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '发布状态：PENDING待发布/PROCESSING发布中/PUBLISHED已发布/FAILED发布失败',
    `retry_count` INT NOT NULL DEFAULT 0 COMMENT '发布失败次数',
    `max_retry_count` INT NOT NULL DEFAULT 10 COMMENT '最大发布重试次数',
    `next_retry_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次可发布时间',
    `processing_at` DATETIME NULL COMMENT '抢占处理时间',
    `processing_by` VARCHAR(128) NULL COMMENT '发布实例标识',
    `published_at` DATETIME NULL COMMENT 'Kafka send 确认时间',
    `last_error` VARCHAR(512) NULL COMMENT '最近发布错误摘要',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_lifecycle_outbox_event_id` (`event_id`),
    KEY `idx_lifecycle_outbox_publish` (`status`, `next_retry_at`, `id`),
    KEY `idx_lifecycle_outbox_operation` (`operation_id`, `created_at`),
    KEY `idx_lifecycle_outbox_causation` (`causation_event_id`),
    KEY `idx_lifecycle_outbox_trace` (`trace_id`),
    KEY `idx_lifecycle_outbox_processing` (`status`, `processing_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号生命周期事务Outbox';

-- -----------------------------------------------------------------------------
-- 7. 手机号绑定历史：手机号是可变凭据，不是订单/资产归属主键
-- 历史数据回填由应用任务执行；phone_ciphertext 允许 NULL 仅用于迁移窗口。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `customer_phone_binding_history` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `customer_id` BIGINT NOT NULL COMMENT '乘客ID，对应 customer.id',
    `binding_version` BIGINT NOT NULL COMMENT '该乘客手机号绑定版本，从1递增',
    `status` VARCHAR(16) NOT NULL COMMENT '绑定状态：ACTIVE当前有效/REPLACED已被新手机号替换/RELEASED账号注销后已释放',
    `phone_ciphertext` VARBINARY(512) NULL COMMENT '手机号密文；历史回填完成后应用层要求非空',
    `phone_identity_hash` CHAR(64) NOT NULL COMMENT 'HMAC-SHA256手机号身份摘要，用于审计/反作弊，不可反解',
    `hash_key_version` VARCHAR(32) NOT NULL COMMENT 'HMAC密钥版本，用于轮换与过期治理',
    `change_operation_no` VARCHAR(64) NULL COMMENT '关联 PHONE_CHANGE/ACCOUNT_CANCEL 操作号',
    `change_reason` VARCHAR(64) NOT NULL COMMENT '变更原因：REGISTER注册/PHONE_CHANGE更换手机号/ACCOUNT_CANCEL注销账号/MIGRATION历史迁移',
    `valid_from` DATETIME NOT NULL COMMENT '绑定生效时间',
    `valid_to` DATETIME NULL COMMENT '绑定失效或释放时间',
    `retention_until` DATETIME NULL COMMENT '密文或身份摘要最晚保留时间，具体期限由合规定版',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `active_customer_id` BIGINT GENERATED ALWAYS AS (
        CASE WHEN `status` = 'ACTIVE' THEN `customer_id` ELSE NULL END
    ) STORED COMMENT '每个乘客最多一个 ACTIVE 手机号绑定',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_customer_phone_binding_version` (`customer_id`, `binding_version`),
    UNIQUE KEY `uk_customer_phone_binding_active` (`active_customer_id`),
    KEY `idx_customer_phone_identity_history` (`phone_identity_hash`, `hash_key_version`, `valid_to`),
    KEY `idx_customer_phone_operation` (`change_operation_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='乘客手机号版本化绑定历史';

-- =============================================================================
-- 上线前必须完成但不应在本 SQL 中伪造的数据工作：
-- 1. 使用应用侧 KMS/密钥配置为现存 customer 回填手机号绑定历史。
-- 2. P2 统一切换后仅以 customer.auth_epoch 为认证权威，不读取、扫描或回填旧 Redis 会话版本键。
-- 3. participant 服务增加 (operation_id, step_code) 唯一 Inbox，并保证 Inbox 与副作用同事务。
-- 4. account-lifecycle 启动时校验 YAML 计划、participant/step_code 契约与唯一 ACTIVE 版本。
-- 5. 灰度阶段仍保留旧 settings 接口，但内部转调新 Lifecycle Application Service。
-- =============================================================================
