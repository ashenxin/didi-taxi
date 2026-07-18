# 订单与派单 TEST

本文覆盖 `order`、`capacity` 的服务级回归，重点验证订单状态机、Transactional Outbox、Kafka 消费幂等、司机池、确认窗、迟滞匹配和并发边界。端侧交互验收见《第一期MVP_乘客派单司机闭环_TEST.md》。

## 0. 通过标准

- `trip_order` 和 `order_event` 是订单权威；Redis 仅作索引。
- 创建订单与写 Outbox 必须同事务成功或失败。
- 重复消息、重复请求和并发动作不得产生重复订单、重复有效事件或双重接单。
- 司机主动拒单/到达前取消写 30 分钟隔离键；确认窗超时不写。
- 总等待 180 秒从创建时间累计，改派不重置。

## 1. 环境与数据

### 1.1 依赖

- MySQL：order、capacity 库。
- Redis：GEO、Presence、司机订单索引、隔离键。
- Kafka：topic `order.dispatch.requested.v1`，consumer group `capacity.order.dispatch.requested.v1`。
- XXL-JOB：order/capacity executor 已注册。
- 可选启动 passenger-api、driver-api 做端到端验证。

### 1.2 测试数据

准备：

- 乘客 P1、P2。
- 同城司机 D1、D2、D3，均有车辆和 companyId。
- D1 距上车点小于 1km，D2 约 2km，D3 超过 3km。
- D1 Presence 新鲜；D2 可切换为过期；D3 用于距离边界。
- 无司机城市 C0，用于迟滞匹配和总等待超时。

每条用例记录 orderNo、outbox eventId、Kafka partition/offset、driverId，避免串单。

## 2. 创建与请求级幂等

### T-DISPATCH-01 创建订单与 Outbox 原子性

步骤：调用主入口创建订单，随后查询 `trip_order`、`order_event`、`order_outbox_event`。

预期：

- HTTP 返回 `CREATED` 和唯一 orderNo。
- `trip_order` 一行；创建事件一条。
- 同事务存在 `DISPATCH_REQUESTED` Outbox，初始 `PENDING`。
- Outbox payload 的 orderNo、乘客、城市、起点坐标、schemaVersion 与订单一致。

### T-DISPATCH-02 创建事务回滚

模拟 Outbox 插入失败或订单必填字段约束失败。

预期：订单、事件、Outbox 全部回滚；不得出现“有订单无派单事件”或孤立 Outbox。

### T-DISPATCH-03 `Idempotency-Key` 同请求重放

同 key、同 body 串行和并发请求至少两次。

预期：返回同一 orderNo；只存在一笔订单、一条创建事件、一条有效派单 Outbox；`order_idempotent_record` 保存请求 hash 与结果。

### T-DISPATCH-04 同 key 不同请求

保持 key 不变，修改终点、坐标或产品线。

预期：返回 409；不得复用旧订单假装新请求成功，也不得新建第二笔订单。

### T-DISPATCH-05 缺失 key 和非法请求

测试缺 `Idempotency-Key`、空 key、非法坐标、缺城市/产品线。

预期：400；数据库无半成品记录。

## 3. Outbox 发布

### T-DISPATCH-06 正常发布

手动触发 `orderOutboxPublish` 或等待调度。

预期：`PENDING -> PROCESSING -> PUBLISHED`；Kafka key=orderNo；payload 带 string 类型 eventId；publishedAt 有值。

### T-DISPATCH-07 发布失败重试

暂停 Kafka 后触发发布。

预期：retryCount 增加；未达上限时回到 `PENDING` 且 nextRetryAt 后移；订单状态不被错误取消。

### T-DISPATCH-08 达上限转 FAILED

把失败次数推进到 `order.outbox.publisher.max-retry-count`。

预期：状态为 `FAILED`；失败原因可查询；不会继续无限重试刷日志。

### T-DISPATCH-09 手动 retry

对 FAILED 记录调用内部 retry 接口，再恢复 Kafka。

预期：合法 FAILED/超时 PROCESSING 可回到 PENDING 并最终 PUBLISHED；对已 PUBLISHED 记录重试应拒绝或无副作用。

### T-DISPATCH-10 PROCESSING 崩溃回收

模拟发布器领取后崩溃，等待 processing timeout，再触发扫描。

预期：记录可回收；多个发布器通过 CAS 最多一个同时处理。

## 4. Kafka 消费与选司机

### T-DISPATCH-11 正常消费

让 P1 订单消息到达 capacity。

预期：`capacity_processed_event` 记录 consumerGroup/eventId/orderNo、处理状态；从 GEO + Presence 选择合法候选，成功后结果为 `SUCCESS`。

### T-DISPATCH-12 重复消息

向 topic 重放完全相同 eventId。

预期：唯一键 `(consumer_group,event_id)` 去重；不重复 assign/openOffer，不新增重复业务事件。

### T-DISPATCH-13 非法与坏消息

分别发送非法 JSON、缺 eventId/orderNo、schemaVersion 不支持、坐标非法。

预期：记录 `MALFORMED/INVALID` 或明确日志；消息被 ack，不阻塞同分区后续合法消息；当前没有 DLQ，原始问题必须可从诊断记录定位。

### T-DISPATCH-14 最近司机和 3km 边界

D1、D2、D3 同时在线。

预期：优先 D1；D3 永不入候选。将 D1 下线后应选择 D2。

### T-DISPATCH-15 Presence 过滤

保留 D2 GEO 成员但让 Presence 超时。

预期：D2 被过滤；不得因 GEO 残留或 DB 可接单而回退选择 stale 司机。

### T-DISPATCH-16 Top3 冲突重选

让第一候选在 assign 前进入服务中。

预期：同一次消费尝试后续候选；最终只指派一人；冲突分类和最终候选可从日志/诊断记录确认。

### T-DISPATCH-17 无司机

在 C0 下单。

预期：消费结果 `NO_DRIVER`；订单保持 `CREATED`；Kafka 不为“无司机”反复重投，等待迟滞匹配。

## 5. assign、offer 与状态机

### T-DISPATCH-18 assign CAS

并发把同一 CREATED 订单指派给 D1/D2。

预期：仅一个 CAS 成功；driverId/carId/companyId 是同一候选快照；失败方 409/冲突，不覆盖成功结果。

### T-DISPATCH-19 openDriverOffer

对已 assign 订单打开确认窗。

预期：进入 `PENDING_DRIVER_CONFIRM`；offerExpiresAt 约为当前时间 +30 秒；offerRound/lastOfferAt 更新；司机索引包含订单。

### T-DISPATCH-20 重复打开 offer

对相同订单重复或并发 openOffer。

预期：不重复延长确认窗，不产生多个同时有效 offer，不写重复状态事件。

### T-DISPATCH-21 接单

D1 在确认窗内 accept。

预期：`PENDING_DRIVER_CONFIRM -> ACCEPTED`；acceptedAt 有值；司机归属校验生效；乘客收到变更通知后详情一致。

### T-DISPATCH-22 多笔待确认互斥

D1 同时有两笔待确认订单，只接其中一笔。

预期：只能形成一笔 ACCEPTED；其它待确认指派按状态机释放/收敛，不能让司机同时服务多单。

## 6. 超时、拒单与改派

### T-DISPATCH-23 offer 超时

让确认窗过期，触发 `orderOfferTimeoutScan`。

预期：订单回 `CREATED`，清空本轮司机/车辆/公司/offer 字段，写 `ORDER_OFFER_TIMED_OUT`，新建派单 Outbox；不写司机-乘客隔离键。

### T-DISPATCH-24 主动拒单

D1 调用 reject。

预期：订单回 CREATED 并重派；写拒单事件；Redis `tx:dispatch:block:dp:{D1}:{P1}` TTL 约 30 分钟；新一轮不得再次选择 D1。

### T-DISPATCH-25 到达前取消

D1 接单后、到达前取消。

预期：`ACCEPTED -> CREATED` 重派，不生成乘客取消终态；写隔离键和明确 reasonCode。

### T-DISPATCH-26 到达后禁止释放

订单 ARRIVED/STARTED 后调用拒单、司机取消或登出释放。

预期：状态冲突；订单保持在途，不生成新派单 Outbox。

### T-DISPATCH-27 隔离过期

确认隔离期内过滤 D1；过期后 D1 在仍在线且合法时可重新成为候选。

## 7. 迟滞匹配与 Presence 清理

### T-DISPATCH-28 司机入池触发迟滞匹配

C0 订单保持 CREATED 后，让 D1 在该城市上线。

预期：入池事件优先触发匹配，订单无需乘客轮询即可推进。

### T-DISPATCH-29 XXL 兜底匹配

模拟入池触发遗漏，运行 `capacityLateDispatchScan`。

预期：找到待派单并完成 assign/openOffer；与入池事件并发时 CAS 防止重复指派。

### T-DISPATCH-30 心跳与清理

司机上线并定期 heartbeat，随后停止心跳并运行 `capacityDriverPresenceCleanup`。

预期：心跳刷新 GEO/Presence；超时后 DB 下线并移除索引；batch limit 生效且不会误清理新鲜司机。

## 8. 行程与总等待

### T-DISPATCH-31 合法行程推进

依次 accept、arrive、start、finish。

预期：只允许 `ACCEPTED -> ARRIVED -> STARTED -> FINISHED`；时间字段和 order_event 顺序一致；重复相同动作幂等或无重复副作用。

### T-DISPATCH-32 乱序动作

CREATED 直接 arrive、ACCEPTED 直接 finish、FINISHED 再 cancel。

预期：全部拒绝，状态不变，不写误导事件。

### T-DISPATCH-33 180 秒总等待取消

准备 CREATED、ASSIGNED、PENDING_DRIVER_CONFIRM 三种超时订单，触发 `orderCreatedDispatchTimeoutScan`。

预期：均按 createdAt 累计取消，cancelBy=系统、reasonCode=DISPATCH_TIMEOUT；改派次数不重置计时；ACCEPTED 订单不被该任务取消。

## 9. 诊断接口

### T-DISPATCH-34 按订单查询链路

调用 order `dispatch-trace`、outbox by-order 和 capacity by-order。

预期：能串联订单状态、Outbox、eventId、消费结果和卡点建议；不存在订单返回 404/空结果语义明确。

### T-DISPATCH-35 汇总与失败列表

构造 PENDING、PROCESSING、PUBLISHED、FAILED。

预期：summary 数量、最老等待年龄和 failed 列表准确；limit 边界受控。

## 10. 自动化与验收证据

```bash
mvn -pl order test
mvn -pl capacity test
mvn -pl passenger-api test
mvn -pl driver-api test
mvn verify
```

2026-07-18 已补充的 capacity 自动化包括：

- `DispatchRequestedConsumerTest`：正常派单、重复事件短路、Top3 首候选冲突后换候选、无司机结论与 ack。
- `ProcessedEventServiceTest`：H2 真实唯一索引下 `(consumer_group,event_id)` 去重，以及不同 consumer group 的隔离。
- `OfferRescheduleServiceTest`：同司机续开确认窗、跨司机改派、改派失败不移动 Redis 待确认索引。

端到端验收还应保存：订单三张关键表快照、Redis GEO/Presence/隔离键、Kafka offset、XXL 执行日志和同一 orderNo 的跨服务日志。

## 11. 2026-07-17 实测结果

- 创建订单、Outbox、Kafka 消费与 capacity 异步派单成功，consumer lag 为 0。
- 订单 `202607170951274283210` 经确认超时重派后由司机 `900001` 接单，并完成 `ACCEPTED → ARRIVED → STARTED → FINISHED`。
- 同一 `Idempotency-Key`、同一请求内容重复提交返回同一 `orderNo`，未创建重复订单；同 key 不同内容冲突语义正常。
- Redis GEO 中可查询测试司机，派单使用的司机、车辆、公司与订单详情一致。
- 管理后台时间线可读出 `ORDER_CREATED`、`ORDER_ASSIGNED`、`ORDER_OFFER_OPENED`、`ORDER_OFFER_TIMED_OUT`、`ORDER_ACCEPTED`、`ORDER_DRIVER_ARRIVED`、`ORDER_TRIP_STARTED`、`ORDER_FINISHED`，状态 7 已正确映射。
- 已知非正确性问题：重复下单请求仍会在 passenger-api 命中 order 幂等记录前重新计算地图路线/估价；不会产生重复订单，但后续可增加 BFF 级结果短路以减少外部调用。
- 当日未通过项（后续已补齐）：完单后尚未自动创建/推进 `trip_order_settlement`；当前实现状态见下节。

## 12. 2026-07-18 自动化补强结果

- capacity 派单、改派和事件幂等新增 9 项测试；`capacity` 模块合计 15 项通过。
- 重复 `eventId` 不再重复调用选司机、assign 或 openOffer；临时关闭短路时测试可捕获重复副作用。
- 完单结算 MVP 已由 `TripSettlementFlowIntegrationTest` 等测试覆盖：完单后登记并推进结算，不再属于“尚未自动创建结算”的未通过项。
- `mvn verify` 全仓通过；JaCoCo 行覆盖率基线为 order 44.43%、capacity 26.31%。
- 单元/集成测试仍不能替代 MySQL、Redis、Kafka、XXL-JOB 和真实端侧同时运行的跨服务验收。
