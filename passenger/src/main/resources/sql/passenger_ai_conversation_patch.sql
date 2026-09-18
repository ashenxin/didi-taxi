-- =============================================================================
-- passenger 库：乘客端 AI 客服长期会话增量 DDL
--
-- 本脚本只负责建立两类长期会话数据：
-- 1. passenger_ai_conversation：乘客可长期查看的会话主记录。
-- 2. passenger_ai_message：完整且按顺序追加的用户消息和 AI 回复。
-- 地点、路线选项及完整路线由 map-service 短时缓存；本脚本不创建或删除旧路线任务表。
--
-- 设计边界：
-- - 完整聊天历史以本脚本的业务表为准，不能只依赖 Spring AI 的有限消息窗口。
-- - conversation_no、message_no 和 request_no 均由服务端生成，不能信任客户端自报归属。
-- - 查询会话时必须同时使用当前认证的 customer_id 和 conversation_no，防止跨乘客访问。
-- - 消息归属由 conversation_id 关联会话表取得，查询消息前先校验会话归属；消息表不重复保存 customer_id。
-- - 用户主动删除和账号注销都使会话立即不可查看；注销后重新注册产生新的 customer_id，
--   新账号不能继承或查询旧账号的 AI 会话。
-- - 消息 JSON 只保存展示摘要；地图地点、完整折线和导航步骤以 map-service 的有效缓存为准。
-- - JSON 中不得写入 API Key、访问令牌、系统提示词、手机号或其他与对话无关的敏感信息。
-- - 不建立数据库外键，关联完整性由 passenger-service 在本地事务中维护，与本仓库现有方式一致。
-- - CREATE TABLE IF NOT EXISTS 只会创建缺失表；已经存在的同名表不会被自动升级。
-- =============================================================================

USE `passenger`;

-- -----------------------------------------------------------------------------
-- 1. 会话主表
--
-- last_message_sequence 是会话内消息序号的分配依据。新增消息时，应在短事务中锁定或使用
-- row_version 做 CAS，递增该字段后再写消息，不能用“查询最大值 + 1”的无锁方式生成序号。
-- memory_summary 只是供模型读取的派生摘要，完整历史始终以 passenger_ai_message 为准。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `passenger_ai_conversation` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `conversation_no` VARCHAR(64) NOT NULL COMMENT '对外稳定会话编号，由服务端生成',
    `customer_id` BIGINT NOT NULL COMMENT '乘客ID，对应 customer.id；所有读写必须校验当前认证乘客',
    `create_idempotency_key` VARCHAR(128) NOT NULL
        COMMENT '创建会话幂等键；同一乘客使用相同键重试时返回原会话',
    `scene_code` VARCHAR(32) NOT NULL DEFAULT 'ROUTE_WAYPOINT'
        COMMENT '会话场景：ROUTE_WAYPOINT指定途经点；后续可扩展FARE_EXPLAIN费用说明',
    `title` VARCHAR(128) NULL COMMENT '会话列表标题，可由首条用户消息生成并允许后续更新',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        COMMENT '会话状态：ACTIVE可查看/DELETED已删除且不可查看',
    `last_message_sequence` BIGINT NOT NULL DEFAULT 0
        COMMENT '会话内已分配的最大消息序号，必须在事务内递增',
    `memory_summary` TEXT NULL
        COMMENT '供模型使用的较早消息摘要；属于可重建派生数据，不代替完整聊天历史',
    `summary_through_sequence` BIGINT NOT NULL DEFAULT 0
        COMMENT 'memory_summary已覆盖到的消息序号，0表示尚未生成摘要',
    `row_version` BIGINT NOT NULL DEFAULT 0 COMMENT '会话并发更新CAS版本',
    `active_request_no` VARCHAR(64) NULL
        COMMENT '当前正在执行的Agent请求编号；请求完成、失败或超时恢复后清空',
    `active_request_started_at` DATETIME(3) NULL
        COMMENT '当前Agent请求开始时间，用于判断超过60秒的失联请求',
    `last_message_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        COMMENT '最近一条消息时间，用于会话列表排序',
    `delete_reason` VARCHAR(32) NULL
        COMMENT '删除原因：USER_DELETE乘客主动删除/ACCOUNT_CANCEL账号注销',
    `delete_operation_no` VARCHAR(64) NULL
        COMMENT '账号注销触发删除时关联的生命周期操作号；乘客主动删除时为空',
    `deleted_at` DATETIME(3) NULL COMMENT '逻辑删除时间；非空后乘客端不得再查看',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_passenger_ai_conversation_no` (`conversation_no`),
    UNIQUE KEY `uk_passenger_ai_conversation_create_idempotency`
        (`customer_id`, `create_idempotency_key`),
    UNIQUE KEY `uk_passenger_ai_conversation_active_request` (`active_request_no`),
    KEY `idx_passenger_ai_conversation_customer_list`
        (`customer_id`, `deleted_at`, `last_message_at`, `id`),
    KEY `idx_passenger_ai_conversation_customer_scene`
        (`customer_id`, `scene_code`, `created_at`, `id`),
    KEY `idx_passenger_ai_conversation_delete_operation`
        (`delete_operation_no`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
    COMMENT='乘客端AI客服长期会话';

-- -----------------------------------------------------------------------------
-- 2. 完整消息历史表
--
-- 每一轮共用同一个 request_no，用户消息与对应 AI 回复通过 request_no 和
-- reply_to_message_id关联。client_message_no保存最长128字符的Idempotency-Key，用于拦截
-- 客户端超时重试造成的重复提问或重复选择。
-- payload_json 只保存前端重放消息卡片所需的结构化展示摘要；完整路线由 map-service 短时缓存。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `passenger_ai_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `message_no` VARCHAR(64) NOT NULL COMMENT '对外稳定消息编号，由服务端生成',
    `conversation_id` BIGINT NOT NULL COMMENT '所属会话ID，对应 passenger_ai_conversation.id',
    `sequence_no` BIGINT NOT NULL COMMENT '会话内严格递增序号，由会话主表在短事务中分配',
    `request_no` VARCHAR(64) NOT NULL COMMENT '一次用户提问及其AI回复共用的服务端请求编号',
    `client_message_no` VARCHAR(128) NULL COMMENT '客户端Idempotency-Key；仅用户消息需要填写',
    `reply_to_message_id` BIGINT NULL COMMENT 'AI回复所对应的用户消息ID',
    `role` VARCHAR(16) NOT NULL COMMENT '消息角色：USER乘客/ASSISTANT智能客服',
    `message_type` VARCHAR(32) NOT NULL DEFAULT 'TEXT'
        COMMENT '消息类型：TEXT文本/ROUTE_CARD路线卡片/ROUTE_OPTIONS路线方案/PLACE_CHOICES地点选项',
    `content` TEXT NOT NULL COMMENT '乘客端展示的消息正文；禁止保存隐藏系统提示词',
    `payload_json` JSON NULL
        COMMENT '前端卡片展示快照；不得包含API Key、token、系统提示词或无关敏感信息',
    `status` VARCHAR(16) NOT NULL DEFAULT 'COMPLETED'
        COMMENT '消息状态：GENERATING生成中/COMPLETED已完成/FAILED生成失败',
    `model_provider` VARCHAR(32) NULL COMMENT 'AI模型提供方，例如DASHSCOPE',
    `model_name` VARCHAR(64) NULL COMMENT '生成该回复时实际使用的模型名称',
    `prompt_tokens` INT NULL COMMENT '模型输入Token数量；提供方未返回时为空',
    `completion_tokens` INT NULL COMMENT '模型输出Token数量；提供方未返回时为空',
    `failure_code` VARCHAR(64) NULL COMMENT '生成失败的稳定错误码',
    `failure_message` VARCHAR(512) NULL COMMENT '脱敏错误摘要，不保存请求密钥和外部响应原文',
    `completed_at` DATETIME(3) NULL COMMENT '消息生成完成或失败时间',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_passenger_ai_message_no` (`message_no`),
    UNIQUE KEY `uk_passenger_ai_message_sequence` (`conversation_id`, `sequence_no`),
    UNIQUE KEY `uk_passenger_ai_message_client_idempotency`
        (`conversation_id`, `client_message_no`),
    KEY `idx_passenger_ai_message_request` (`request_no`, `id`),
    KEY `idx_passenger_ai_message_reply` (`reply_to_message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
    COMMENT='乘客端AI客服完整消息历史';

-- =============================================================================
-- 应用实现时必须配套的事务规则：
-- 1. 第一段短事务：按 customer_id + conversation_no 校验归属和会话状态，占用当前
--    active_request_no、分配消息序号并写入乘客消息；同会话只允许一个活动请求。
-- 2. 提交事务后再调用模型和地图服务，不持有数据库事务等待外部接口。地点、路线选项和
--    完整路线的可信数据留在 map-service 短时缓存，不能从历史消息恢复过期地图事实。
-- 3. 第二段短事务：重新核对会话归属、状态及 active_request_no，写入客服回复或失败消息
--    并释放活动请求；路线卡片只保存 routeRef、地点名称、里程和预计时间等展示摘要。
-- 4. 同一幂等键重试返回第一次结果；迟到的模型或地图结果不得覆盖新请求或已删除会话。
--    客服消息成功持久化后，才向乘客发送对应的完成事件或路线卡片。
-- 5. 两张表的物理清理期限由产品和数据治理方案确认后另行增加清理任务。
--    旧路线任务表如需删除，应在核对实际环境和历史数据后独立执行，本脚本不做删除。
-- =============================================================================
