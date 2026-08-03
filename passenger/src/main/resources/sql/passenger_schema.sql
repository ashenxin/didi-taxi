-- =============================================================================
-- passenger 库：建表（乘客 customer / OAuth 绑定 / 后台 sys_*）
-- 种子数据见 passenger_seed.sql
-- =============================================================================

-- 乘客服务 passenger 库（表结构后续按需补充）
CREATE DATABASE IF NOT EXISTS `passenger` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE `passenger`;

CREATE TABLE IF NOT EXISTS `customer` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',

    `phone` VARCHAR(32) NOT NULL COMMENT '手机号，登录主键；未删除记录在业务上唯一',
    `password_hash` VARCHAR(128) NULL COMMENT '密码摘要；若仅短信登录可无',
    `nickname` VARCHAR(64) NULL COMMENT '昵称',
    `avatar_url` VARCHAR(512) NULL COMMENT '头像地址',

    `status` INT NOT NULL DEFAULT 0 COMMENT '账号状态：0 正常，1 冻结等（枚举可后续统一）',

    `lifecycle_status` VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        COMMENT '账户生命周期：ACTIVE正常有效/CANCELLING注销处理中/CANCELLED已注销',
    `lifecycle_version` BIGINT NOT NULL DEFAULT 0
        COMMENT '生命周期 CAS 版本；换号、注销受理、撤销、完成时递增',
    `auth_epoch` BIGINT NOT NULL DEFAULT 0
        COMMENT '认证代次的数据库权威；敏感变更后递增，Redis 仅作缓存投影',
    `current_lifecycle_operation_no` VARCHAR(64) NULL
        COMMENT '当前未结束生命周期操作号；仅用于快速定位，权威进度在 operation 表',
    `cancelled_at` DATETIME NULL COMMENT '最终完成账号注销时间；进入 CANCELLING 时不得提前填写',

    `real_name` VARCHAR(64) NULL COMMENT '真实姓名，按需实名',
    `id_card_no` VARCHAR(32) NULL COMMENT '证件号，敏感字段，存储需加密/脱敏策略',

    `is_deleted` INT NOT NULL DEFAULT 0 COMMENT '逻辑删除，0 未删除',

    `phone_active` VARCHAR(32) GENERATED ALWAYS AS (CASE WHEN `is_deleted` = 0 THEN `phone` ELSE NULL END) STORED
        COMMENT '未删除时等于 phone，已删除为 NULL；唯一索引保证「未删」手机号不重复',

    `created_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_customer_phone_active` (`phone_active`),
    KEY `idx_customer_lifecycle_status` (`lifecycle_status`, `updated_at`),
    KEY `idx_customer_current_lifecycle_operation` (`current_lifecycle_operation_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='乘客用户';

-- =============================================================================
-- 乘客账号换号 / 注销统一生命周期中心
-- 存量数据库升级仍使用 passenger_account_lifecycle_patch.sql。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `account_lifecycle_operation` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `operation_no` VARCHAR(64) NOT NULL COMMENT '对外稳定操作号',
    `customer_id` BIGINT NOT NULL COMMENT '乘客ID，对应 customer.id',
    `operation_type` VARCHAR(32) NOT NULL COMMENT 'PHONE_CHANGE/ACCOUNT_CANCEL',
    `status` VARCHAR(32) NOT NULL COMMENT 'REQUESTED/FENCED/VALIDATING/BLOCKED/EXECUTING/RETRY_PENDING/MANUAL_REVIEW/COMPLETED/ABORTED',
    `idempotency_key` VARCHAR(128) NOT NULL COMMENT '客户端幂等键',
    `request_hash` CHAR(64) NOT NULL COMMENT '规范化请求 SHA-256；同键改内容返回冲突',
    `expected_lifecycle_version` BIGINT NOT NULL COMMENT '客户端提交时看到的账户生命周期版本',
    `applied_lifecycle_version` BIGINT NULL COMMENT '建立栅栏或完成换号后写入的账户版本',
    `plan_code` VARCHAR(64) NOT NULL COMMENT '创建时固化的计划编码',
    `plan_version` INT NOT NULL COMMENT '创建时固化的计划版本',
    `plan_digest` CHAR(64) NOT NULL COMMENT '规范化 YAML 计划的 SHA-256',
    `irreversible_started` TINYINT NOT NULL DEFAULT 0 COMMENT '0未开始/1已开始；开始后只允许前向恢复',
    `restricted_auth_epoch` BIGINT NULL COMMENT '受限生命周期会话使用的认证代次',
    `active_blocker_count` INT NOT NULL DEFAULT 0 COMMENT '当前未解除 blocker 数量',
    `row_version` BIGINT NOT NULL DEFAULT 0 COMMENT 'Operation 状态推进 CAS 版本',
    `next_wakeup_at` DATETIME NULL COMMENT '下次恢复扫描时间',
    `last_error_code` VARCHAR(64) NULL COMMENT '最近流程错误码',
    `last_error_message` VARCHAR(512) NULL COMMENT '最近流程错误摘要，不得包含敏感原文',
    `request_context` JSON NULL COMMENT '脱敏审计上下文，不得含 OTP/token',
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

CREATE TABLE IF NOT EXISTS `account_lifecycle_step` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `operation_id` BIGINT NOT NULL COMMENT 'account_lifecycle_operation.id',
    `step_code` VARCHAR(64) NOT NULL COMMENT '稳定步骤编码',
    `participant_code` VARCHAR(32) NOT NULL COMMENT 'ORDER/WALLET/CALCULATE/IDENTITY/SESSION/ACCOUNT',
    `phase` VARCHAR(24) NOT NULL COMMENT 'PRECONDITION/ACTION/RETENTION/POST_ACTION/FINALIZE',
    `execution_mode` VARCHAR(24) NOT NULL COMMENT 'SYNC_CHECK/ASYNC_COMMAND/LOCAL_TRANSACTION',
    `criticality` VARCHAR(24) NOT NULL COMMENT 'REQUIRED/POST_ACTION/MANUAL_IF_FAILED',
    `sequence_no` INT NOT NULL COMMENT '步骤顺序；同序号可并行',
    `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCEEDED/BLOCKED/RETRY_PENDING/MANUAL_REVIEW/SKIPPED/CANCELLED',
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
    `step_config` JSON NULL COMMENT '从 YAML 固化的非敏感步骤配置',
    `command_snapshot` JSON NULL COMMENT '脱敏命令审计快照',
    `result_snapshot` JSON NULL COMMENT '脱敏结果审计快照',
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

CREATE TABLE IF NOT EXISTS `account_lifecycle_blocker` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `operation_id` BIGINT NOT NULL COMMENT 'account_lifecycle_operation.id',
    `step_id` BIGINT NULL COMMENT '发现该 blocker 的 account_lifecycle_step.id',
    `domain_code` VARCHAR(32) NOT NULL COMMENT 'ORDER/WALLET/CALCULATE/IDENTITY',
    `blocker_key` VARCHAR(191) NOT NULL COMMENT '领域内稳定阻断键',
    `blocker_type` VARCHAR(64) NOT NULL COMMENT '结构化阻断类型',
    `resource_type` VARCHAR(64) NULL COMMENT 'TRIP_ORDER/PAYMENT/REFUND/COUPON',
    `resource_id` VARCHAR(128) NULL COMMENT '关联业务号；不得使用明文手机号',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/RESOLVED/WAIVED',
    `resolution_actions` JSON NULL COMMENT '允许用户执行的解阻动作代码列表',
    `snapshot_json` JSON NULL COMMENT '发现时的脱敏业务快照',
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

CREATE TABLE IF NOT EXISTS `account_lifecycle_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `event_id` VARCHAR(64) NOT NULL COMMENT '全局唯一事件ID',
    `operation_id` BIGINT NOT NULL COMMENT 'account_lifecycle_operation.id',
    `customer_id` BIGINT NOT NULL COMMENT '乘客ID',
    `event_type` VARCHAR(64) NOT NULL COMMENT '状态迁移或人工动作类型',
    `from_status` VARCHAR(32) NULL COMMENT '变更前状态',
    `to_status` VARCHAR(32) NULL COMMENT '变更后状态',
    `actor_type` VARCHAR(24) NOT NULL COMMENT 'CUSTOMER/SYSTEM/ADMIN/SERVICE',
    `actor_id` VARCHAR(64) NULL COMMENT '操作者ID；系统任务可为空',
    `reason_code` VARCHAR(64) NULL COMMENT '稳定原因码',
    `trace_id` VARCHAR(64) NULL COMMENT '全链路 trace/request id',
    `payload_snapshot` JSON NULL COMMENT '脱敏审计快照，不得包含敏感原文',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_lifecycle_event_id` (`event_id`),
    KEY `idx_lifecycle_event_operation_time` (`operation_id`, `created_at`, `id`),
    KEY `idx_lifecycle_event_customer_time` (`customer_id`, `created_at`, `id`),
    KEY `idx_lifecycle_event_type_time` (`event_type`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号生命周期审计事件';

CREATE TABLE IF NOT EXISTS `account_lifecycle_outbox` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `event_id` VARCHAR(64) NOT NULL COMMENT '全局唯一事件ID，同时作为消费者去重键',
    `operation_id` BIGINT NULL COMMENT 'account_lifecycle_operation.id；注册初始化事件为空',
    `aggregate_type` VARCHAR(32) NOT NULL DEFAULT 'ACCOUNT_LIFECYCLE',
    `aggregate_id` VARCHAR(64) NOT NULL COMMENT 'operation_no或CUSTOMER:{customerId}',
    `event_type` VARCHAR(64) NOT NULL COMMENT '命令或领域事件类型',
    `causation_event_id` VARCHAR(64) NULL COMMENT '触发本消息的 lifecycle event_id',
    `trace_id` VARCHAR(64) NULL COMMENT '全链路追踪ID',
    `target_domain` VARCHAR(32) NULL COMMENT '命令目标领域；广播事件可为空',
    `topic` VARCHAR(128) NOT NULL COMMENT 'Kafka topic',
    `partition_key` VARCHAR(128) NOT NULL COMMENT '默认 customerId，保证同账号事件有序',
    `payload` JSON NOT NULL COMMENT '只含ID、版本、原因码和时间，不含明文手机号',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/PUBLISHED/FAILED',
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

CREATE TABLE IF NOT EXISTS `customer_phone_binding_history` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `customer_id` BIGINT NOT NULL COMMENT '乘客ID，对应 customer.id',
    `binding_version` BIGINT NOT NULL COMMENT '该乘客手机号绑定版本，从1递增',
    `status` VARCHAR(16) NOT NULL COMMENT 'ACTIVE/REPLACED/RELEASED',
    `phone_ciphertext` VARBINARY(512) NULL COMMENT '手机号密文；历史回填完成后应用层要求非空',
    `phone_identity_hash` CHAR(64) NOT NULL COMMENT 'HMAC-SHA256手机号身份摘要',
    `hash_key_version` VARCHAR(32) NOT NULL COMMENT 'HMAC密钥版本',
    `change_operation_no` VARCHAR(64) NULL COMMENT '关联生命周期操作号',
    `change_reason` VARCHAR(64) NOT NULL COMMENT 'REGISTER/PHONE_CHANGE/ACCOUNT_CANCEL/MIGRATION',
    `valid_from` DATETIME NOT NULL COMMENT '绑定生效时间',
    `valid_to` DATETIME NULL COMMENT '绑定失效或释放时间',
    `retention_until` DATETIME NULL COMMENT '密文或身份摘要最晚保留时间',
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


CREATE TABLE IF NOT EXISTS `customer_oauth_binding` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `customer_id` BIGINT NOT NULL COMMENT 'customer.id',
    `provider` VARCHAR(32) NOT NULL COMMENT '第三方标识，如 wechat_mp、wechat_app、alipay',
    `provider_user_id` VARCHAR(128) NOT NULL COMMENT '第三方用户唯一标识，如 openid、unionid 视 provider 而定',
    `raw_profile_json` JSON NULL COMMENT '可选：授权后拉取的用户信息快照',
    `created_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_oauth_provider_user` (`provider`, `provider_user_id`),
    KEY `idx_oauth_customer` (`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='乘客第三方账号绑定';

-- -----------------------------------------------------------------------------
-- 角色
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_role` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `code`         VARCHAR(64)  NOT NULL COMMENT 'SUPER / PROVINCE_ADMIN / CITY_OPERATOR',
    `name`         VARCHAR(128) NOT NULL COMMENT '展示名称',
    `remark`       VARCHAR(255)          DEFAULT NULL,
    `sort`         INT          NOT NULL DEFAULT 0,
    `status`       INT          NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`   INT          NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_role_code` (`code`),
    KEY `idx_sys_role_status` (`status`, `is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台角色';

-- -----------------------------------------------------------------------------
-- 后台用户（与 passenger.customer 无关）
-- token_version：改角色/权限/省市区/禁用/改密后递增，用于 JWT 立即失效
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_user` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT,
    `username`        VARCHAR(64)   NOT NULL COMMENT '登录名',
    `password_hash`   VARCHAR(255)  NOT NULL COMMENT 'BCrypt 等',
    `display_name`    VARCHAR(128)           DEFAULT NULL,
    `province_code`   VARCHAR(32)            DEFAULT NULL COMMENT '省编码；超管NULL；省管必填；市员与city配套',
    `city_code`       VARCHAR(32)            DEFAULT NULL COMMENT '市编码；超管、省管NULL；市员必填',
    `token_version`   BIGINT        NOT NULL DEFAULT 0 COMMENT 'JWT 版本，敏感变更后 +1',
    `status`          INT           NOT NULL DEFAULT 1 COMMENT '1正常 0停用等',
    `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_by`      BIGINT                 DEFAULT NULL COMMENT '创建人 sys_user.id',
    `updated_by`      BIGINT                 DEFAULT NULL,
    `is_deleted`      INT           NOT NULL DEFAULT 0,
    `username_active` VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN `is_deleted` = 0 THEN `username` ELSE NULL END) STORED
        COMMENT '未删时等于 username，已删为 NULL；唯一保证在用登录名不重复',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_user_username_active` (`username_active`),
    KEY `idx_sys_user_province_city` (`province_code`, `city_code`, `status`, `is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台用户';

-- -----------------------------------------------------------------------------
-- 用户-角色
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_user_role` (
    `user_id`    BIGINT   NOT NULL,
    `role_id`    BIGINT   NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`user_id`, `role_id`),
    KEY `idx_sys_user_role_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户-角色';

-- -----------------------------------------------------------------------------
-- 菜单（权限清单 + 前端路由）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_menu` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT,
    `parent_id`   BIGINT                 DEFAULT NULL,
    `path`        VARCHAR(255)  NOT NULL COMMENT '路由 path',
    `name`        VARCHAR(128)  NOT NULL COMMENT '菜单标题',
    `icon`        VARCHAR(64)            DEFAULT NULL,
    `component`   VARCHAR(255)           DEFAULT NULL COMMENT '前端组件路径',
    `perms`       VARCHAR(256)           DEFAULT NULL COMMENT '权限标识，如 order:list',
    `sort`        INT           NOT NULL DEFAULT 0,
    `visible`     INT           NOT NULL DEFAULT 1 COMMENT '1显示 0隐藏',
    `status`      INT           NOT NULL DEFAULT 1,
    `created_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_sys_menu_parent` (`parent_id`, `sort`),
    KEY `idx_sys_menu_status` (`status`, `is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台菜单';

-- -----------------------------------------------------------------------------
-- 角色-菜单
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_role_menu` (
    `role_id`    BIGINT   NOT NULL,
    `menu_id`    BIGINT   NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`role_id`, `menu_id`),
    KEY `idx_sys_role_menu_menu` (`menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色-菜单';
