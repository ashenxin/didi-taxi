# 第一期 MVP：乘客派单司机闭环（TEST）

> 本文档用于第一期 MVP 的**验收回归**，覆盖“已实现可测”与“未实现但本期计划”的测试用例。  
> 接口与 JSON 示例以 `第一期MVP_乘客派单司机闭环_API.md` 为准；产品口径以 `第一期MVP_乘客派单司机闭环_PRD.md` 为准。

---

## 0. 测试范围与口径

### 0.1 本期关键验收口径（必须一致）

- **等待态归并**：派单/待接/改派中均算“等待态”，乘客页面可统一展示。
- **总体等待 180 秒兜底系统取消**：从下单成功开始累计，改派不重置；到期仍未接单 → 系统取消（原因语义“暂无车辆/无人接单”）。
- **原因采集轻量**：前端单选、无长文本输入；不对端展示原因。
- **到达后禁止通过退出登录/取消结束本单**：以服务端状态（ARRIVED）裁决。

### 0.2 用例标记

- **[✅已实现可测]**：仓库已有接口与逻辑，可直接联调回归
- **[🟨本期计划-未实现]**：PRD/接口已定义，当前代码尚未落地；用于占位验收与开发对齐

---

## 1. 环境与数据准备

### 1.1 基础环境

- MySQL、Redis 可用
- 服务启动：`gateway`、`passenger-api`、`driver-api`、`order`、`capacity`（端到端还需 `map`、`calculate`）

### 1.2 测试账号

- 乘客账号可登录拿到 `accessToken`
- 司机账号可登录拿到 `accessToken`，且 driver 在 capacity 侧满足可接单条件（`can_accept_order=1`、`city_code` 非空等）

### 1.3 测试位置建议（提高稳定性）

- 司机上线时给定与乘客上车点**同城且距离近**的 lat/lng
- 若环境支持固定坐标（geo-pin 等），建议开启以提高复现稳定性

### 1.4 定时任务 / XXL-JOB：触发方式与“加速回归”

> 说明：本闭环存在“状态由服务端异步推进”的场景（超时取消、改派推进等）。测试时可能需要**等待扫描周期**，或由测试人员**主动触发一次任务**以快速验证。

#### 1.4.1 哪些用例依赖“定时扫描/调度任务”

- **T-PA-03 总体等待 180 秒兜底系统取消**：需要服务端扫描把订单推到 `CANCELLED`
- **T-PA-04 / T-DR-03 / T-DR-04（改派相关）**：若实现为“拒单/取消后由调度推进下一轮”，同样依赖扫描推进（以实现为准）

#### 1.4.2 等待抖动的测试口径

- 由于扫描是周期性执行，验收口径允许 **180s + 扫描间隔** 的抖动（例如扫描 30s，则最多多等一个扫描周期）

#### 1.4.3 本地/测试环境的“加速回归”建议（优先）

- **缩短超时参数**：在测试环境把 `order.dispatch.wait-timeout-seconds` 临时改小（如 10~30s），把 `order.dispatch.timeout-scan-interval-ms` 改小（如 1000~5000ms），以便快速覆盖超时取消相关用例  
  - 说明：改动仅用于测试环境；验证完成后需恢复默认值（如 180s / 30000ms）

#### 1.4.4 若使用 XXL-JOB：如何“手动触发一次”

- 若项目将超时取消/改派推进做成 XXL-JOB 任务：
  - 测试同学可在 XXL-JOB 控制台对对应任务执行一次 **“手动触发/执行一次”**，以便立刻推进状态，减少等待
  - 执行后通过以下接口验证结果：
    - 乘客侧：`GET /app/api/v1/orders/{orderNo}`
    - 事件时间线：`GET /api/v1/orders/{orderNo}/events`

> 注意：不论是 XXL-JOB 还是 Spring `@Scheduled`，都必须保证“重复执行无害”（CAS/幂等），测试触发多次不应导致状态乱跳。

---

## 2. Smoke（冒烟用例）

### T-SM-01 乘客登录并下单成功（同步链路）

- **标记**：[✅已实现可测]
- **接口**：
  - `POST /app/api/v1/auth/login-sms`
  - `POST /app/api/v1/orders`
- **步骤**：
  1. 乘客登录拿 token
  2. 乘客下单
- **预期**：
  - 下单返回 `code=200`，`data.orderNo` 非空
  - `data.status.en` 为 `CREATED` 或 `ASSIGNED`

### T-SM-02 司机上线并能拉到指派列表

- **标记**：[✅已实现可测]
- **接口**：
  - `POST /driver/api/v1/drivers/{driverId}/online`
  - `GET /driver/api/v1/orders/assigned`
- **步骤**：
  1. 司机上线听单（online=true）
  2. 拉取指派列表
- **预期**：
  - 上线成功 `code=200`
  - 指派列表接口正常返回（可为空列表）

---

## 3. 乘客端用例

### T-PA-01 下单 → 订单详情轮询可读

- **标记**：[✅已实现可测]
- **接口**：
  - `POST /app/api/v1/orders`
  - `GET /app/api/v1/orders/{orderNo}`
- **步骤**：
  1. 下单获取 `orderNo`
  2. 轮询订单详情 5~10 次
- **预期**：
  - 始终 `code=200`
  - 状态可从 `CREATED/ASSIGNED` 推进到后续状态（若司机配合接单）

### T-PA-02 乘客取消（等待态）

- **标记**：[✅已实现可测]
- **接口**：
  - `POST /app/api/v1/orders`
  - `POST /app/api/v1/orders/{orderNo}/cancel`
  - `GET /app/api/v1/orders/{orderNo}`
- **步骤**：
  1. 下单进入等待态
  2. 调用取消
  3. 查询详情确认终态
- **预期**：
  - 订单进入取消终态（`status.en=CANCELLED`）
  - `cancelBy/cancelReason` 有值（字段名以实际 VO 为准）

### T-PA-03 总体等待 180 秒兜底系统取消（无人接单）

- **标记**：[🟨本期计划-未实现]（当前仓库已实现 CREATED 超时取消；但 PRD 要求“全等待态累计 180s”）
- **前置**：保证无人接单（无司机上线/司机拒单/司机不接）
- **接口**：
  - `POST /app/api/v1/orders`
  - `GET /app/api/v1/orders/{orderNo}`
- **步骤**：
  1. 下单后持续轮询，直至超过 180 秒（可通过缩短配置加速回归）
- **预期**：
  - 超时后订单进入系统取消终态
  - 原因语义为“暂无车辆/无人接单”

### T-PA-04 “重新派单/改派中”展示触发（司机拒单/取消/超时收回）

- **标记**：[✅已实现可测]（司机拒单/到达前取消已落地；**超时收回**依赖 order 确认窗口扫描，与既有用例一致）
- **接口（司机侧触发改派）**：
  - `POST /driver/api/v1/orders/{orderNo}/reject`
  - `POST /driver/api/v1/orders/{orderNo}/cancel`（仅 **已接单、到达前**）
- **步骤（思路）**：
  1. 乘客下单并派到某司机（或进入待接窗口）
  2. 触发司机拒单 / 司机取消 / 超时收回（任选其一或组合回归）
  3. 乘客轮询详情
- **预期**：
  - 订单回到 **`CREATED`** 后再次进入派单链路；乘客侧表现为“等待态继续 + 正在重新派单”语义（不需要用户操作；展示以 passenger-api 为准）
  - 总等待 180s 不重置
  - 细化校验：订单详情 `reDispatching=true` 时，乘客端文案显示“正在为您重新派单”；`reDispatching=false` 时显示常规等待文案（如“派单中”）

---

## 4. 司机端用例

### T-DR-01 接单（ASSIGNED/PENDING → ACCEPTED）

- **标记**：[✅已实现可测]
- **接口**：
  - `GET /driver/api/v1/orders/assigned`
  - `POST /driver/api/v1/orders/{orderNo}/accept`
  - `GET /app/api/v1/orders/{orderNo}`（乘客侧验证）
- **步骤**：
  1. 司机拉取指派单
  2. 对其中一单接单
  3. 乘客侧轮询确认状态变为“司机已接单”
- **预期**：
  - 接单成功 `code=200`
  - 乘客详情状态进入 `ACCEPTED`

### T-DR-02 到达/开始/完单状态推进

- **标记**：[✅已实现可测]
- **接口**：
  - `POST /driver/api/v1/orders/{orderNo}/arrive`
  - `POST /driver/api/v1/orders/{orderNo}/start`
  - `POST /driver/api/v1/orders/{orderNo}/finish`
- **步骤**：按顺序调用
- **预期**：
  - 状态依次进入 `ARRIVED → STARTED → FINISHED`
  - 非法顺序应失败并提示（视当前实现返回码/文案）

### T-DR-03 司机拒单（未接单阶段）

- **标记**：[✅已实现可测]
- **接口**：
  - BFF：`POST /driver/api/v1/orders/{orderNo}/reject`
  - order-core：`POST /api/v1/orders/{orderNo}/reject`
- **步骤**：
  1. 司机看到待接单
  2. 拒单（reasonCode 单选）
  3. 乘客侧轮询
- **预期**：
  - 该单对该司机失效（不再可接）；`order_event` 含 **`ORDER_DRIVER_REJECTED`**
  - 订单 **`CREATED`** 后重新派单；乘客进入重新派单等待态（不展示原因）
  - 新增校验：30 分钟隔离期内，不应再次派给同一司机（司机刷新指派单不应再看到该乘客该单）

### T-DR-04 司机取消（已接单后、到达前）

- **标记**：[✅已实现可测]
- **接口**：
  - BFF：`POST /driver/api/v1/orders/{orderNo}/cancel`
  - order-core：`POST /api/v1/orders/{orderNo}/driver/cancel`
- **步骤**：
  1. 司机接单成功
  2. 到达前取消（reasonCode 单选）
  3. 乘客侧轮询
- **预期**：
  - 订单 **`CREATED`** 后重新派单（不展示原因）；`order_event` 含 **`ORDER_DRIVER_CANCELLED_BEFORE_ARRIVE`**
  - 司机不可继续推进该单（`GET .../orders/{orderNo}` 对原司机应 **403**；`arrive`/`start`/`finish` 应失败）
  - 新增校验：30 分钟隔离期内，不应再次派给同一司机（司机刷新指派单不应再看到该乘客该单）

### T-DR-06 司机-乘客隔离匹配（30 分钟）

- **标记**：[✅已实现可测]
- **目的**：验证“司机拒单/到达前取消后，30 分钟内不再匹配同一乘客”。
- **步骤**：
  1. 乘客 A 下单，司机 D 被派到单并可见待接。
  2. 司机 D 执行拒单（或先接单再到达前取消）。
  3. 触发重新派单（等待正常调度或手动触发一次调度任务）。
  4. 在 30 分钟内，多次让司机 D 刷新指派单列表。
  5. 30 分钟后再次触发派单并刷新司机 D 指派单。
- **预期**：
  - 30 分钟内：该乘客订单不会再派给司机 D，司机 D 的待接列表也看不到该乘客该单。
  - 30 分钟后：该隔离自动失效，可再次参与匹配（是否命中仍取决于距离/可接单状态等）。

### T-DR-05 到达后禁止取消/登出取消

- **标记**：[🟨本期计划-未实现]（**登出联动取消**未落地；**到达后司机取消**：`POST .../cancel` 仅在 **`ACCEPTED`** 合法，**`ARRIVED` 及之后**应返回业务错误如 **409**）
- **步骤**：
  1. 司机接单并到达（ARRIVED）
  2. 尝试司机取消 / 司机退出登录触发取消
- **预期**：
  - 到达后不允许通过司机取消接口结束本单（返回明确错误提示）
  - 登出联动取消仍以 PRD 为准（未实现则本步可记为「待办」）

---

## 5. 登出联动用例（本期 PRD 新增）

### T-LO-01 司机登出：token 失效（已实现）

- **标记**：[✅已实现可测]
- **接口**：`POST /driver/api/v1/auth/logout`
- **步骤**：司机登出后再次请求任意司机业务接口
- **预期**：业务接口返回 401

### T-LO-02 乘客登出：token 失效（passenger-api 已实现）

- **标记**：[✅乘客端已实现可测]
- **接口**：`POST /app/api/v1/auth/logout`（须 Bearer）
- **步骤**：乘客登出后再次请求任意乘客业务接口（如 `GET /app/api/v1/orders/{orderNo}`）
- **预期**：业务接口返回 401；`passenger-api` 校验 JWT `tv` 与 Redis `passenger:tv:{customerId}` 一致

### T-LO-03 到达前退出登录触发取消本单

- **标记**：[✅乘客路径已实现可测] / [🟨司机路径-仍按计划补齐]
- **步骤（两条路径都要覆盖）**：
  - **乘客**：等待态/已派单/已接单但未到达 → `POST /app/api/v1/auth/logout`
  - **司机**：已接单但未到达 → `POST /driver/api/v1/auth/logout`（运力侧联动仍见 gap 总览）
- **预期**：
  - **乘客**：本单进入取消终态，取消原因含「乘客退出登录」语义；响应 `data.hint` 可含已代取消说明
  - **司机**：本单进入取消终态（与运力/订单规则一致）

### T-LO-04 到达后退出登录不触发取消

- **标记**：[✅乘客路径已实现可测] / [🟨司机路径-仍按计划补齐]
- **步骤**：订单进入 ARRIVED 后，乘客/司机退出登录
- **预期**：
  - 订单不被取消
  - **乘客**：`POST /app/api/v1/auth/logout` 仍成功登出；`data.hint` 提示无法通过退出登录取消（司机已到达或行程已开始）

---

## 6. 排障建议（联调必备）

给定 `orderNo`：

1) 先查乘客详情接口 `GET /app/api/v1/orders/{orderNo}`：看 `status/cancelBy/cancelReason/timestamps`
2) 再查 order 时间线 `GET /api/v1/orders/{orderNo}/events`：看是否发生 `ORDER_ASSIGNED/ORDER_ACCEPTED/ORDER_CANCELLED/ORDER_OFFER_TIMED_OUT` 等
3) 若“派不到单”：查 capacity 最近司机 `GET /api/v1/dispatch/nearest-driver` 与司机听单状态
4) 若“同司机一直拿不到该乘客单”：查 Redis 隔离键 `tx:dispatch:block:dp:{driverId}:{passengerId}` 与剩余 TTL

---

## 7. 与仓库现有测试清单的关系

仓库已有 `功能测试清单.md` 覆盖全域模块；本文档仅聚焦“第一期乘客派单司机闭环”，并补齐 PRD 相关用例（**拒单/司机取消已可测**；登出联动、总体 180s 全等待态口径等待办项见各用例标记）。

---

## 8. 联调 Checklist（司机-乘客 30 分钟隔离）

- [ ] 准备 1 名乘客 A、2 名司机（D1/D2），确认 D1/D2 都在线听单且可接单。
- [ ] A 下单并确认先派给 D1（记录 `orderNo`、`driverId`、`passengerId`）。
- [ ] D1 执行拒单（或接单后到达前取消），确认 `order_event` 出现对应事件。
- [ ] 立即触发重新派单，确认订单继续流转，但不会再次派到 D1。
- [ ] 在 30 分钟内重复刷新 D1 指派单，确认看不到该乘客该单。
- [ ] 检查 Redis 存在键 `tx:dispatch:block:dp:D1:A`，且 TTL 在倒计时。
- [ ] 30 分钟后再次触发派单，确认 D1 恢复候选资格（命中与否由距离/状态决定）。
- [ ] 回归普通链路：D2 可正常接单并推进 `ACCEPTED → ARRIVED → STARTED → FINISHED`。

