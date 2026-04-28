# 后台管理系统：运力配置 API（接口契约）

本文档定义管理端「运力配置/换队审核」对外 API（`admin-api`）以及 `admin-api` 依赖的下游接口形状（用于联调与契约对齐）。  
产品口径与技术实现分别见《后台管理系统_运力配置_PRD.md》《后台管理系统_运力配置_TECH.md》。

---

## 1. 通用约定

- **鉴权**：`Authorization: Bearer <JWT>`（除全站登录接口外）。
- **响应体**：`ResponseVo<T>`；管理端与 capacity successful 业务码多为 `200`（与各自 `ExceptionCode` 一致，以实现为准）。
- **数据域**：见《后台管理系统_权限与接口文档.md》§4.7（BFF 合并省/市；换队审核需基于 `driverCityCode` 校验）。

---

## 2. 对外接口（`admin-api`）

### 2.1 运力配置（公司/司机/车辆）

**前缀**：`/admin/api/v1/capacity`

| Method | Path | 说明 |
|--------|------|------|
| GET | `/companies` | 公司分页 |
| GET | `/companies/{id}` | 公司详情（未删除） |
| POST | `/companies` | 新增「公司+车队」 |
| PUT | `/companies/{id}` | 仅更新公司名称/车队名称 |
| DELETE | `/companies/{id}` | 逻辑删除（有归属司机时下游拒绝） |
| GET | `/drivers` | 司机分页 |
| GET | `/drivers/{driverId}` | 司机档案详情（含证件/影像等，不含密码；越界 404） |
| GET | `/drivers/{driverId}/cars` | 某司机名下车辆分页（先校验司机域） |
| GET | `/cars` | 独立车辆分页（越界筛选 403；资源越界 404） |
| GET | `/cars/{id}` | 车辆详情（含司机/公司字段；越界 404） |

#### 2.1.1 公司分页 GET `/companies`

**Query**：`pageNo`、`pageSize`、`provinceCode?`、`cityCode?`、`companyNo?`、`companyName?`

#### 2.1.2 新增公司+车队 POST `/companies`

**Body**

- `provinceCode`、`provinceName`
- `cityCode`、`cityName`
- `companyNo`、`companyName`
- `team`（车队名称）
- `teamId?`：为空则下游自动生成全库唯一业务编码

#### 2.1.3 更新公司 PUT `/companies/{id}`

仅更新：

- `companyName`（必填非空）
- `team`（必填非空）

#### 2.1.4 司机分页 GET `/drivers`

**Query**：`pageNo`、`pageSize`、`companyId?`、`name?`、`phone?`、`online?`、`provinceCode?`、`cityCode?`、`canAcceptOrder?`、`auditStatus?`

#### 2.1.5 司机详情 GET `/drivers/{driverId}`

- 成功 `data`：`AdminDriverDetailVO`（证件/驾驶证日期/影像 OSS URL/状态等，不含密码）
- 可附带 `companyName`（BFF 按 `companyId` 补全）
- 先校验司机 `cityCode` 数据域：越界 404

#### 2.1.6 独立车辆分页 GET `/cars`

**Query**：`pageNo`、`pageSize`、`provinceCode?`、`cityCode?`、`companyId?`、`driverId?`、`driverPhone?`、`carNo?`、`brandName?`、`rideTypeId?`

说明：省/市与登录域合并；越界筛选 403。

---

### 2.2 换队审核（Team Change Requests）

**前缀**：`/admin/api/v1/capacity/team-change-requests`

| Method | Path | 说明 |
|--------|------|------|
| GET | `/pending-count` | 待审核条数（角标；带登录省/市域） |
| GET | `/` | 分页（域由 BFF 注入） |
| GET | `/{id}` | 详情；跨域 404 |
| POST | `/{id}/approve` | 通过；body 可选 `reviewReason`；Query `reviewedBy?` |
| POST | `/{id}/reject` | 拒绝；body `reviewReason` 必填；Query `reviewedBy?` |

说明：司机端提交换队申请若走 app/API，以 capacity app 路径为准；管理端是否支持「透传提交」以代码为准。

---

## 3. 下游依赖（`capacity-service` 直连接口摘要）

### 3.1 公司

| Method | Path |
|--------|------|
| GET | `/api/v1/companies` |
| GET | `/api/v1/companies/{id}` |
| POST | `/api/v1/companies` |
| PUT | `/api/v1/companies/{id}`（Body：`companyName`、`team`） |
| DELETE | `/api/v1/companies/{id}` |

### 3.2 司机

| Method | Path |
|--------|------|
| GET | `/api/v1/drivers` |
| GET | `/api/v1/drivers/{driverId}` |
| GET | `/api/v1/drivers/{driverId}/cars` |
| POST | `/api/v1/drivers/{driverId}/online` |
| GET | `/api/v1/drivers/{driverId}/accept-readiness` |

司机分页 Query（摘要）：`pageNo`、`pageSize`、`companyId`、`name`、`phone`、`online`、`provinceCode`、`cityCode`、`canAcceptOrder`、`auditStatus`。

- **省筛选**：优先 `province_code = provinceCode`（国标省码 6 位）；历史行 `province_code` 为空时退回 `city_code LIKE 省码前两位%`
- **市筛选**：`cityCode` 精确等于 `city_code`
- **审核状态 `auditStatus`**：取值 0～3（0 待完善、1 审核中、2 通过、3 驳回/需补件），不再使用 4

### 3.3 换队（管理）

| Method | Path |
|--------|------|
| GET | `/api/v1/admin/driver-team-change-requests` |
| GET | `/api/v1/admin/driver-team-change-requests/{id}` |
| POST | `/api/v1/admin/driver-team-change-requests/{id}/approve` |
| POST | `/api/v1/admin/driver-team-change-requests/{id}/reject` |

换队分页可带 `provinceCode`、`cityCode`（按司机档案过滤）；列表/详情 VO 含 `driverCityCode`（供 BFF 域校验）。

### 3.4 车辆

| Method | Path |
|--------|------|
| GET | `/api/v1/cars` |
| GET | `/api/v1/cars/{id}` |

车辆分页 Query 常用：`pageNo`、`pageSize`、`provinceCode?`、`cityCode?`、`companyId?`、`driverId?`、`driverPhone?`、`carNo?`、`brandName?`、`rideTypeId?`。  
其中 `provinceCode`/`cityCode` 按车辆 `city_code` 过滤（省筛选为市码前缀匹配）。

---

## 4. Feign 对照（`admin-api`）

`CapacityClient`（`services.capacity.base-url`）：`companyPage`、`companyDetail`、`createCompany`、`updateCompany`、`deleteCompany`、`driverPage`、`driverDetail`、`carsByDriver`、`driverTeamChangePage`、`driverTeamChangeDetail`、`approveDriverTeamChange`、`rejectDriverTeamChange`。

---

## 5. 错误码（摘要）

| 场景 | 典型 HTTP / code |
|------|------------------|
| 未登录 | 401 |
| 筛选项越出账号省/市 | 403 |
| 资源不在域内 / 不存在（掩蔽） | 404 |
| 参数校验失败 | 400 |
| 下游错误 body | BFF 透传或统一 502 |

---

## 6. 库表与脚本（`capacity` 库）

脚本路径相对于仓库 `capacity/src/main/resources/sql/`。新环境建议直接使用全量 DDL。

| 脚本 | 说明 |
|------|------|
| `capacity_schema.sql` | 全量建库建表（`company` / `driver` / `car` / `driver_team_change_request` / `driver_audit_record`；`driver` 含 `province_code`、`province_name` 及 `idx_driver_province_code`） |
| `capacity_seed.sql` | 演示数据（`TRUNCATE` 后写入；与订单/计价联调 id 对齐） |

`driver.audit_status`：0 待完善、1 审核中、2 通过、3 驳回/需补件（无 4；若存量库存在 4，可手工更新为 3）。

---

## 7. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-04-03 | 首版：替代原运力 TODO 接口章；对齐 `companyId`、域裁剪与司机详情路径 |
| 2026-04-15 | 公司：`GET/POST/DELETE /companies`，`team_id` 全库唯一；`PUT /companies/{id}` 仅更新 `companyName/team`。司机：`GET /drivers/{driverId}` 档案详情；分页 Query 含 `canAcceptOrder`、`auditStatus`；省筛优先 `province_code`；审核状态 0～3（弃用 4） |
| 2026-04-17 | 脚本合并为 `capacity_schema.sql` / `capacity_seed.sql` |

