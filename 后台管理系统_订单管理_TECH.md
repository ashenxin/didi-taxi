# 后台管理系统：订单管理 TECH（技术设计）

本文档描述管理端「订单管理」的技术边界、调用链路与关键实现约束。  
接口契约见《后台管理系统_订单管理_API.md》，产品规则见《后台管理系统_订单管理_PRD.md》。

---

## 1. 系统边界与职责

### 1.1 服务划分

- **`admin-api`（BFF）**
  - 对外前缀：`/admin/api/v1/orders`
  - 校验 JWT、执行数据域（`AdminDataScope`）校验与合并
  - 通过 Feign/HTTP 调用 `order-service`、`passenger-service`
  - **不直连订单库**
  - 近期增强：聚合 `order-service` Outbox 诊断与 `capacity-service` Kafka 消费结果，形成后台排障接口
- **`order-service`**
  - 订单主表（如 `trip_order`）、订单事件表（如 `order_event`）
  - 提供分页、详情、事件列表查询接口
  - 提供内部 outbox / dispatch-trace 诊断接口
- **`passenger-service`**
  - 按手机号查乘客、按 id 查乘客（用于列表筛选与详情补手机号）
- **`capacity-service`**
  - 提供派单 Kafka 消费结果诊断接口，用于按 `eventId` 或 `orderNo` 查询消费状态

---

## 2. 数据权限（实现口径）

数据域规则与全站一致，来源见《后台管理系统_权限与接口文档.md》§4.7。

### 2.1 列表查询

- **非 SUPER**
  - 将请求 `provinceCode`/`cityCode` 与 JWT 内的域做合并/约束
  - 若请求条件越界：返回 403
- **SUPER**
  - 不锁定省/市，按请求条件下发到下游

### 2.2 详情查询

- 非 SUPER：若订单不在本账号域内，返回 404（统一对外文案「订单不存在」）
- SUPER：直接查询

---

## 3. 聚合策略（避免 N+1）

### 3.1 列表

- **禁止逐行补手机号**：列表接口不返回 `passengerPhone`
- 当按 `phone` 筛选时：
  - 先调用 `passenger-service: /customers/by-phone` 得到 `passengerId`
  - 乘客不存在：直接返回空页（200）
  - 乘客存在：将 `passengerId`（或等价条件）下发到 `order-service` 完成分页

### 3.2 详情

- 详情接口可在拿到订单后基于 `passengerId` **一次**调用 `passenger-service: /customers/{id}` 补手机号

---

## 4. 调用链路（参考）

### 4.1 列表（无手机号筛选）

`didi-Vue` → `admin-api GET /admin/api/v1/orders` → `order-service GET /api/v1/orders`

### 4.2 列表（手机号筛选）

`didi-Vue` → `admin-api GET /admin/api/v1/orders?phone=...`  
→ `passenger-service GET /api/v1/customers/by-phone?phone=...`  
→（存在乘客）`order-service GET /api/v1/orders?...&passengerId=...`  
→（不存在乘客）返回空页

### 4.3 详情（含事件）

`didi-Vue` → `admin-api GET /admin/api/v1/orders/{orderNo}`  
→ `order-service GET /api/v1/orders/{orderNo}`  
→ `order-service GET /api/v1/orders/{orderNo}/events`  
→（可选）`passenger-service GET /api/v1/customers/{passengerId}`

### 4.4 派单诊断聚合（近期增强）

`didi-Vue` → `admin-api GET /admin/api/v1/orders/{orderNo}/dispatch-diagnostics`
→ `order-service GET /api/v1/orders/internal/dispatch-trace/{orderNo}`
→ `order-service GET /api/v1/orders/internal/outbox/by-order/{orderNo}`
→ `capacity-service GET /api/v1/dispatch/internal/events/by-order/{orderNo}`

手动重试：

`didi-Vue` → `admin-api POST /admin/api/v1/orders/outbox/{id}/retry`
→ `order-service POST /api/v1/orders/internal/outbox/{id}/retry`

> BFF 需先按订单详情执行数据域校验，避免通过诊断接口绕过后台订单权限。

---

## 5. 错误码与网关行为

- 约定 `ResponseVo.code` 与 HTTP 状态一致（401/403/404/502/504）
- 下游不可用/超时：`admin-api` 统一透出 502/504（细化可后续增强，但首期不要求区分）

---

## 6. 联调数据与一致性约束

### 6.1 演示数据脚本

- `passenger_seed.sql`：提供演示乘客（用于手机号筛选/详情补手机号）
- `capacity_seed.sql`：司机/车辆/公司（如后续需要在详情聚合展示）
- `order_seed.sql`：15 笔订单 + `ORDER_*` 事件时间线

### 6.2 事件排序

- 下游 `GET /api/v1/orders/{orderNo}/events` 建议按 `occurredAt` 升序返回
- 若存在缺失时间，前端可用 `createdAt` 兜底展示（是否兜底属于产品/前端策略）

### 6.3 行政区划展示（前端）

前端展示逻辑需与国标码约定对齐，直辖市区县码场景要保持一致（详见 API 文档联调说明）。

---

## 7. 前端联调建议（近期增强）

- 订单排障页先做只读聚合，重试按钮仅对 `FAILED` outbox 显示。
- 状态文案统一展示英文状态 + 中文释义，例如 `PENDING_DRIVER_CONFIRM（待司机确认）`。
- capacity 消费结果建议用不同颜色区分：`SUCCESS` 成功、`NO_DRIVER` 无司机、`FAILED` 失败、`INVALID` / `MALFORMED` 坏消息。
- DLQ / 坏消息页后续独立建设，当前订单排障页只展示与该 `orderNo` 相关的消费结果。
