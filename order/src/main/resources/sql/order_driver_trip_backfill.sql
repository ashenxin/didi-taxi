-- MySQL 8；请先备份，在暂停订单写流量的维护窗口手动执行。
-- 先执行同目录建表SQL。按接单事件重建服务片段，只插入缺失记录，不改写已有记录。
-- 历史缺失证据的片段不猜测终态，末尾查询供人工核对。
USE `order`;
START TRANSACTION;

INSERT INTO driver_trip_record
(order_id, order_no, source_key, driver_id, car_id, company_id, city_code, product_code,
 origin_address, dest_address, status, active_flag, estimated_amount, distance_meters,
 distance_source, ordered_at, accepted_at, arrived_at, started_at, finished_at, cancelled_at,
 service_duration_seconds, cancel_by, cancel_reason, updated_at)
-- 接单事件按订单ID划分服务片段；下一次接单事件是本片段右边界。
WITH accepts AS (
  SELECT id AS accept_id, order_id, order_no, operator_id AS driver_id, occurred_at AS accepted_at,
         LEAD(id) OVER (PARTITION BY order_id ORDER BY id) AS next_accept_id
  FROM order_event
  WHERE event_type='ORDER_ACCEPTED' AND operator_type=2 AND operator_id IS NOT NULL
), facts AS (
  -- 只查本片段第一次终止事件；系统/乘客取消不要求司机操作人。
  SELECT a.accept_id, a.order_id, a.order_no, a.driver_id, a.accepted_at, a.next_accept_id,
    MIN(CASE WHEN e.event_type IN ('ORDER_FINISHED','ORDER_CANCELLED','ORDER_DRIVER_CANCELLED_BEFORE_ARRIVE')
      AND (e.event_type='ORDER_CANCELLED' OR (e.operator_type=2 AND e.operator_id=a.driver_id)) THEN e.id END) AS terminal_id
  FROM accepts a LEFT JOIN order_event e ON e.order_id=a.order_id AND e.id>a.accept_id
    AND (a.next_accept_id IS NULL OR e.id<a.next_accept_id)
  GROUP BY a.accept_id,a.order_id,a.order_no,a.driver_id,a.accepted_at,a.next_accept_id
), resolved AS (
  -- 到达与开始必须由本片段司机产生且发生在终止前，避免混入后续司机时间线。
  SELECT f.*, t.event_type AS terminal_type, t.occurred_at AS terminal_at,
    t.operator_type AS terminal_operator, t.reason_code, t.reason_desc,
    (SELECT MIN(e.occurred_at) FROM order_event e WHERE e.order_id=f.order_id AND e.id>f.accept_id
      AND (f.terminal_id IS NULL OR e.id<f.terminal_id) AND (f.next_accept_id IS NULL OR e.id<f.next_accept_id)
      AND e.event_type='ORDER_DRIVER_ARRIVED' AND e.operator_type=2 AND e.operator_id=f.driver_id) AS arrived_at,
    (SELECT MIN(e.occurred_at) FROM order_event e WHERE e.order_id=f.order_id AND e.id>f.accept_id
      AND (f.terminal_id IS NULL OR e.id<f.terminal_id) AND (f.next_accept_id IS NULL OR e.id<f.next_accept_id)
      AND e.event_type='ORDER_TRIP_STARTED' AND e.operator_type=2 AND e.operator_id=f.driver_id) AS started_at
  FROM facts f LEFT JOIN order_event t ON t.id=f.terminal_id
)
-- 车辆/车队仅在主表仍能证明同一次接单时回填；否则保留NULL。
-- final_amount等账单不在本表写入，运行查询只允许最终完单司机关联账单。
SELECT o.id,o.order_no,CONCAT('event:',f.accept_id),f.driver_id,
  CASE WHEN f.next_accept_id IS NULL AND o.driver_id=f.driver_id AND o.accepted_at=f.accepted_at THEN o.car_id END,
  CASE WHEN f.next_accept_id IS NULL AND o.driver_id=f.driver_id AND o.accepted_at=f.accepted_at THEN o.company_id END,
  o.city_code,o.product_code,o.origin_address,o.dest_address,
  CASE WHEN f.terminal_type='ORDER_FINISHED' THEN 5 WHEN f.terminal_id IS NOT NULL THEN 6 ELSE o.status END,
  CASE WHEN f.terminal_id IS NULL AND o.status IN (2,3,4) THEN 1 END,
  o.estimated_amount,o.planned_distance_meters,o.distance_source,o.created_at,f.accepted_at,
  f.arrived_at,f.started_at,
  CASE WHEN f.terminal_type='ORDER_FINISHED' THEN f.terminal_at END,
  CASE WHEN f.terminal_id IS NOT NULL AND f.terminal_type!='ORDER_FINISHED' THEN f.terminal_at END,
  CASE WHEN f.terminal_type='ORDER_FINISHED' AND f.started_at IS NOT NULL AND f.terminal_at>=f.started_at
    THEN TIMESTAMPDIFF(SECOND,f.started_at,f.terminal_at) END,
  CASE WHEN f.terminal_type='ORDER_DRIVER_CANCELLED_BEFORE_ARRIVE' THEN 2
       WHEN f.terminal_type='ORDER_CANCELLED' THEN CASE WHEN f.terminal_operator=1 THEN 1 WHEN f.terminal_operator=2 THEN 2 ELSE 3 END END,
  CASE WHEN f.terminal_type='ORDER_DRIVER_CANCELLED_BEFORE_ARRIVE' THEN COALESCE(f.reason_code,f.reason_desc)
       WHEN f.terminal_type='ORDER_CANCELLED' THEN COALESCE(f.reason_desc,f.reason_code) END,
  COALESCE(f.terminal_at,o.updated_at)
FROM resolved f JOIN trip_order o ON o.id=f.order_id
WHERE f.accepted_at IS NOT NULL
  AND (f.terminal_id IS NOT NULL OR
       (f.next_accept_id IS NULL AND o.driver_id=f.driver_id AND o.accepted_at=f.accepted_at AND o.status IN (2,3,4)))
  AND NOT EXISTS (SELECT 1 FROM driver_trip_record r WHERE r.source_key=CONCAT('event:',f.accept_id)
       OR (r.order_id=f.order_id AND r.driver_id=f.driver_id AND r.accepted_at=f.accepted_at));

-- 兼容缺少接单事件、但当前订单有明确接单时间与服务方的旧订单。
INSERT INTO driver_trip_record
(order_id,order_no,source_key,driver_id,car_id,company_id,city_code,product_code,
 origin_address,dest_address,status,active_flag,estimated_amount,distance_meters,distance_source,
 ordered_at,accepted_at,arrived_at,started_at,finished_at,cancelled_at,service_duration_seconds,cancel_by,cancel_reason,updated_at)
SELECT o.id,o.order_no,CONCAT('legacy-order:',o.id),o.driver_id,o.car_id,o.company_id,o.city_code,o.product_code,
 o.origin_address,o.dest_address,o.status,CASE WHEN o.status IN (2,3,4) THEN 1 END,
 o.estimated_amount,o.planned_distance_meters,o.distance_source,o.created_at,o.accepted_at,o.arrived_at,o.started_at,
 CASE WHEN o.status=5 THEN o.finished_at END,CASE WHEN o.status=6 THEN o.cancelled_at END,
 CASE WHEN o.status=5 AND o.started_at IS NOT NULL AND o.finished_at>=o.started_at THEN TIMESTAMPDIFF(SECOND,o.started_at,o.finished_at) END,
 CASE WHEN o.status=6 THEN o.cancel_by END,CASE WHEN o.status=6 THEN o.cancel_reason END,o.updated_at
FROM trip_order o
WHERE o.driver_id IS NOT NULL AND o.accepted_at IS NOT NULL AND o.status IN (2,3,4,5,6)
 AND NOT EXISTS (SELECT 1 FROM driver_trip_record r WHERE r.order_id=o.id AND r.driver_id=o.driver_id AND r.accepted_at=o.accepted_at)
 AND NOT EXISTS (SELECT 1 FROM driver_trip_record r WHERE r.source_key=CONCAT('legacy-order:',o.id));

-- 提交两阶段补录；任何语句失败应停止并ROLLBACK，不得忽略错误继续提交。
COMMIT;

-- 只读核对：无法凭证据重建的接单事件，应人工核验，不自动猜测。
SELECT e.id AS accept_event_id,e.order_no,e.operator_id AS driver_id,e.occurred_at AS accepted_at
FROM order_event e
WHERE e.event_type='ORDER_ACCEPTED' AND e.operator_type=2
 AND NOT EXISTS (SELECT 1 FROM driver_trip_record r WHERE r.order_id=e.order_id AND r.driver_id=e.operator_id AND r.accepted_at=e.occurred_at);
-- 汇总服务状态数量，与预期历史数据范围核对。
SELECT status,COUNT(*) AS trip_count FROM driver_trip_record GROUP BY status;
-- 核对改派场景：同一订单多次服务应各有记录，归属与取消原因不可互相覆盖。
SELECT order_no,driver_id,accepted_at,status,cancel_by,cancel_reason FROM driver_trip_record ORDER BY order_id,id;
