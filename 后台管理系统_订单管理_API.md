# 后台管理系统：订单管理 API（接口契约）

本文档定义管理端「订单管理」对外 API（`admin-api`）以及 `admin-api` 依赖的下游接口形状（用于联调与契约对齐）。  
产品规则与技术实现细节分别见《后台管理系统_订单管理_PRD.md》《后台管理系统_订单管理_TECH.md》。

---

## 1. 通用约定

- **鉴权**：除登录外，须 `Authorization: Bearer <JWT>`。
- **响应体**：`ResponseVo<T>`；`code == 200` 表示成功；HTTP 状态与 `code` 对齐。
- **数据域**：省/市裁剪与越权规则见《后台管理系统_权限与接口文档.md》§4.7（本能力必须遵守）。

---

## 2. 对外接口（`admin-api`）

**前缀**：`/admin/api/v1/orders`

### 2.1 分页列表

- **Method**：GET
- **Path**：`/admin/api/v1/orders`

**Query**

| 参数 | 必填 | 说明 |
|------|------|------|
| `orderNo` | 否 | 订单号 |
| `phone` | 否 | 乘客手机；仅在无 `orderNo` 时参与查询（见 PRD） |
| `provinceCode` | 否 | 与登录域合并（见 PRD/TECH） |
| `cityCode` | 否 | 与登录域合并（见 PRD/TECH） |
| `status` | 否 | 订单状态 |
| `createdAtStart` | 否 | `yyyy-MM-dd HH:mm:ss` |
| `createdAtEnd` | 否 | `yyyy-MM-dd HH:mm:ss` |
| `pageNo` | 否（默认 1） | |
| `pageSize` | 否（默认 10） | |

**行为摘要**

- 当需要按手机筛选时：`admin-api` 先调用 `passenger-service` 的「按手机号查乘客」获取 `passengerId`；若乘客不存在则直接返回空页（业务 200）。
- 列表行不返回乘客手机号（避免 N+1，详见 PRD）。

**成功响应**

- HTTP：200
- `data`：分页结构（以实现返回为准），列表行字段以 `admin-api` 的 VO 为准。

### 2.2 详情（含事件）

- **Method**：GET
- **Path**：`/admin/api/v1/orders/{orderNo}`

**成功响应**

- HTTP：200
- `data`：`AdminOrderDetailVO`（订单字段 + `events` 列表 + 详情内乘客手机号等，以实现为准）

**失败响应**

- 订单不存在或不在数据域内：HTTP 404，`msg` 如「订单不存在」

---

## 3. 下游依赖（`admin-api` 调用）

### 3.1 `order-service`

| Method | Path | 用途 |
|--------|------|------|
| GET | `/api/v1/orders` | 分页；Query 含 `pageNo`、`pageSize` 及订单筛选项（由 BFF 转发） |
| GET | `/api/v1/orders/{orderNo}` | 详情 |
| GET | `/api/v1/orders/{orderNo}/events` | 事件列表（建议按 `occurredAt` 升序返回） |

### 3.2 `passenger-service`

| Method | Path | 用途 |
|--------|------|------|
| GET | `/api/v1/customers/by-phone?phone=` | 列表按手机号筛选的前置查询 |
| GET | `/api/v1/customers/{passengerId}` | 详情补手机号（`passengerId` 类型以实现为准） |

---

## 4. 错误码（摘要）

| 场景 | HTTP |
|------|------|
| 未登录 / token 无效 | 401 |
| 数据域越权（筛选项） | 403 |
| 订单不存在或不在域内 | 404 |
| 下游不可用 | 502 |
| 超时 / 连接失败 | 504 |

---

## 5. 联调假数据（可选）

用于管理端订单列表/详情联调与演示。

| 项 | 说明 |
|----|------|
| 脚本 | `order/src/main/resources/sql/order_seed.sql`（建表见 `order_schema.sql`） |
| 依赖 | 建议已执行 `passenger_seed.sql`（乘客 `10001`～`10003`）与 `capacity_seed.sql`（司机/车/公司） |
| 订单号 | `OD202604150001`～`OD202604150015` 共 15 条；可重复执行（会先删除同前缀订单及事件再插入） |
| 状态分布 | 覆盖创建、分配、接单、到达、行程中、完单、取消等，便于筛选验收 |
| 事件类型 | `ORDER_CREATED`、`ORDER_ASSIGNED`、`ORDER_ACCEPTED`、`ORDER_DRIVER_ARRIVED`、`ORDER_TRIP_STARTED`、`ORDER_FINISHED`、`ORDER_CANCELLED` |
| 操作方 `operator_type` | 0 系统、1 乘客、2 司机（以代码为准） |

**下游路径（订单服务）**

- 分页：`GET /api/v1/orders`
- 详情：`GET /api/v1/orders/{orderNo}`
- 事件：`GET /api/v1/orders/{orderNo}/events`（按 `occurredAt` 升序）

**前端省市区划展示（对齐前端约定）**

- 列表/详情「省/市」文案使用 `formatGbRegionDisplayName(provinceCode, cityCode)`。
- 直辖市下若 `city_code` 为区县码（例如 `310104`），展示「省/区县」；编码列可展示 `310000 / 310104` 形式（实现位置见前端 `didi-Vue` `utils/regionCodes.js`）。

---

## 6. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-04-03 | 首版：从原订单 TODO 提炼；对齐 JWT、§4.7、当前 BFF 路径 |
| 2026-04-15 | 增补 `order_seed.sql` 联调数据、`operator_type`、直辖市区县展示与编码约定 |

