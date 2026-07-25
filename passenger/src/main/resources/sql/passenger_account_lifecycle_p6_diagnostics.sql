-- P6 只读诊断 SQL：不包含 DDL/DML，不需要作为补丁执行。
USE `passenger`;

-- 1. 到期但尚未恢复的 Operation
SELECT id, operation_no, customer_id, status, irreversible_started,
       next_wakeup_at, last_error_code, updated_at
FROM account_lifecycle_operation
WHERE operation_type = 'ACCOUNT_CANCEL'
  AND status IN ('FENCED', 'VALIDATING', 'EXECUTING', 'RETRY_PENDING', 'MANUAL_REVIEW')
  AND (next_wakeup_at IS NULL OR next_wakeup_at <= NOW())
ORDER BY COALESCE(next_wakeup_at, created_at), id;

-- 2. 已超时且应主动查询参与方结果的 Step
SELECT o.operation_no, s.step_code, s.participant_code, s.attempt_count,
       s.command_event_id, s.timeout_at, s.last_error_code
FROM account_lifecycle_step s
JOIN account_lifecycle_operation o ON o.id = s.operation_id
WHERE s.status = 'RUNNING'
  AND s.timeout_at IS NOT NULL
  AND s.timeout_at <= NOW()
ORDER BY s.timeout_at, s.id;

-- 3. Outbox 积压与最老消息
SELECT status, COUNT(*) AS message_count, MIN(created_at) AS oldest_created_at,
       MIN(next_retry_at) AS earliest_retry_at,
       SUM(CASE WHEN retry_count >= max_retry_count THEN 1 ELSE 0 END)
           AS exhausted_count,
       SUM(CASE WHEN status = 'PROCESSING'
                     AND processing_at <= DATE_SUB(NOW(), INTERVAL 2 MINUTE)
                THEN 1 ELSE 0 END) AS stale_processing_count
FROM account_lifecycle_outbox
WHERE status IN ('PENDING', 'FAILED', 'PROCESSING')
GROUP BY status;

-- 4. 待人工处理步骤（结合 Event 审核人工操作证据）
SELECT o.operation_no, o.customer_id, o.irreversible_started,
       s.step_code, s.participant_code, s.attempt_count,
       s.last_error_code, s.last_error_message, s.updated_at
FROM account_lifecycle_operation o
JOIN account_lifecycle_step s ON s.operation_id = o.id
WHERE o.status = 'MANUAL_REVIEW'
  AND s.status = 'MANUAL_REVIEW'
ORDER BY s.updated_at, s.id;
