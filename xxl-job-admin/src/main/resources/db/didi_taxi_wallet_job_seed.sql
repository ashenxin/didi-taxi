-- Didi Taxi Wallet XXL-JOB seed.
-- Prerequisite: the official XXL-JOB tables have already been created.
-- Executor addresses are discovered from wallet-executor heartbeats; do not hard-code local IPs here.

INSERT INTO xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'wallet-executor', '钱包服务', 0, NULL, NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM xxl_job_group WHERE app_name = 'wallet-executor'
);

SET @wallet_job_group_id := (
    SELECT id FROM xxl_job_group WHERE app_name = 'wallet-executor' ORDER BY id LIMIT 1
);

INSERT INTO xxl_job_info (
    job_group, job_desc, add_time, update_time, author, alarm_email,
    schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
    executor_handler, executor_param, executor_block_strategy, executor_timeout,
    executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
    child_jobid, trigger_status, trigger_last_time, trigger_next_time
)
SELECT
    @wallet_job_group_id, '钱包支付结果通知', NOW(), NOW(), 'admin', '',
    'CRON', '*/5 * * * * ?', 'DO_NOTHING', 'FIRST',
    'walletPaymentResultNotify', '', 'SERIAL_EXECUTION', 30,
    0, 'BEAN', '', 'GLUE代码初始化', NOW(),
    '', 1, 0, UNIX_TIMESTAMP(DATE_ADD(NOW(), INTERVAL 60 SECOND)) * 1000
WHERE NOT EXISTS (
    SELECT 1
    FROM xxl_job_info
    WHERE job_group = @wallet_job_group_id
      AND executor_handler = 'walletPaymentResultNotify'
);

