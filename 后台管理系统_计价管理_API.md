# 后台管理系统：计价管理 API（接口契约）

本文档定义管理端「计价管理」对外 API（`admin-api`）以及 `admin-api` 依赖的下游接口形状（用于联调与契约对齐）。  
产品规则与技术实现分别见《后台管理系统_计价管理_PRD.md》《后台管理系统_计价管理_TECH.md》。

---

## 1. 通用约定

- **鉴权**：`Authorization: Bearer <JWT>`（除全站登录接口外）。
- **响应体**：`ResponseVo<T>`；成功业务码通常为 `200`（以实现为准）。
- **数据域**：见《后台管理系统_权限与接口文档.md》§4.7（列表省/市与登录域合并；读写 body 省/市锁定；跨域资源 404 掩蔽；筛选项越权 403）。

---

## 2. 对外接口（`admin-api`：计价规则）

**前缀**：`/admin/api/v1/pricing/fare-rules`

| Method | Path | 说明 |
|--------|------|------|
| GET | `/` | 分页列表 |
| GET | `/{id}` | 详情（不在域内 404） |
| POST | `/` | 新建（返回新建规则 id） |
| PUT | `/{id}` | 更新（通常返回 data = null） |
| DELETE | `/{id}` | 逻辑删除 |

### 2.1 GET `/` 计价规则分页

**Query**

- `pageNo`：默认 1
- `pageSize`：默认 10
- `companyId?`
- `provinceCode?`
- `cityCode?`
- `productCode?`
- `ruleName?`：模糊
- `active?`
  - `1` 生效中：`effective_to` 为空或大于当前时间
  - `0` 已失效：`effective_to` 非空且小于等于当前时间

### 2.2 GET `/{id}` 详情

- `data`：`AdminFareRuleVO`
  - 包含：`companyId`、`companyNo`、`companyName`（BFF 填充）及规则字段
- 规则不在域内：404

### 2.3 POST `/` 新建

- **Body**：`FareRuleUpsertBody`（字段见下）
- **成功**：`data` 为新建规则 `id`（Long）

### 2.4 PUT `/{id}` 更新

- **Body**：同创建
- **成功**：`data` 通常为 `null`（以实现为准）

### 2.5 DELETE `/{id}` 删除

- 逻辑删除（以 `calculate-service` 行为为准）

### 2.6 `FareRuleUpsertBody` 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `companyId` | Long | 是 | 运力公司主键，对应 `capacity.company.id` |
| `companyNo` | String | 否 | 可省略；保存时 BFF 按公司详情覆盖为 `company_no` |
| `provinceCode` | String | 是 | 国标省码等，须与公司档案一致 |
| `cityCode` | String | 是 | 市/区县码，须与公司档案一致 |
| `productCode` | String | 是 | 产品线编码（如 `ECONOMY`） |
| `ruleName` | String | 否 | 展示名 |
| `effectiveFrom` | LocalDateTime | 是 | 生效开始 |
| `effectiveTo` | LocalDateTime | 否 | 结束时间；空表示长期有效 |
| `baseFare`～`maximumFare` | 金额类 | 见校验 | 起步价、含里程/时长、单价、最低/封顶等（以 calculate DTO 为准） |

---

## 3. 下游依赖（摘要）

### 3.1 `calculate-service`（规则 CRUD）

**前缀**：`/api/v1/fare-rules`

| Method | Path | 说明 |
|--------|------|------|
| GET | `/` | 分页；Query 与 BFF 转发一致（含 `companyId`、`active` 等） |
| GET | `/{id}` | 详情 |
| POST | `/` | 创建；Body：`FareRuleUpsertBody`（含 `companyId`、`companyNo`） |
| PUT | `/{id}` | 更新 |
| DELETE | `/{id}` | 逻辑删除 |

**业务校验（服务端）**

- 同一 `company_id + province + city + product_code` 下，`is_deleted = 0` 的规则有效期区间不得重叠；否则返回业务错误（HTTP/`code` 以全局异常处理器为准）。

### 3.2 `calculate-service`（费用预估，非管理菜单）

| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/v1/calculate/estimate` | Body：`companyId`、`provinceCode`、`cityCode`、`productCode`、`distanceMeters`、`durationSeconds`；未匹配规则时 404 |

### 3.3 `capacity-service`（公司信息补全）

- 列表/详情的 `companyName`：由 BFF 调用 `CapacityClient#companyDetail` 补全（以实现为准）。

---

## 4. Feign 对照（`admin-api`）

- `CalculateClient`（`services.calculate.base-url`）：`page`、`detail`、`create`、`update`、`delete`，路径对应「下游 §3.1」。
- 公司名称补全：`CapacityClient#companyDetail`。

---

## 5. VO 与错误码（摘要）

### 5.1 `AdminFareRuleVO`（列表项/详情）

常用字段：`id`、`companyId`、`companyNo`、`companyName`、`provinceCode`、`cityCode`、`productCode`、`ruleName`、`effectiveFrom`、`effectiveTo`、计费明细字段、`createdAt`、`updatedAt`。

### 5.2 错误码

| 场景 | 典型 HTTP / code |
|------|------------------|
| 未登录 | 401 |
| 筛选项越出账号省/市 | 403 |
| 规则/公司不在域内 | 404 |
| 参数校验失败（含公司与省市不一致、区间重叠） | 400 或业务码（见实现） |
| 下游错误 | 透传或统一 502（见实现） |

---

## 6. 库表与脚本（`calculate` 库）

脚本路径相对于仓库 `calculate/src/main/resources/sql/`。

| 脚本 | 说明 |
|------|------|
| `calculate_schema.sql` | 全量建库建表（`fare_rule`，含 `company_id`、`company_no`，索引 `idx_fare_rule_company_scope`） |
| `calculate_seed.sql` | 种子数据：演示计价规则（公司 id 与 `capacity_seed.sql` 对齐） |

测试用 H2：`calculate/src/test/resources/schema-test.sql` 中 `fare_rule` 定义需与线上一致。

---

## 7. 前端路由约定（`didi-Vue`）

| 路由 | 视图 | 说明 |
|------|------|------|
| `/pricing/fare-rules` | `pricing/FareRuleListView.vue` | 列表 + 公司筛选 |
| `/pricing/fare-rules/new` | `pricing/FareRuleEditView.vue` | 新建（公司下拉，选公司带出省/市） |
| `/pricing/fare-rules/:id` | `pricing/FareRuleEditView.vue` | 编辑 |

菜单与权限标识以 `sys_menu.perms` 为准（含 `visible=0` 的按钮行），与《后台管理系统_权限与接口文档.md》§2 一致（如 `pricing:fare-rule:list`）。

---

## 8. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-04-15 | 首版：计价规则绑定 `company_id` / `company_no` 与产品线；补充 BFF、calculate 路径与 VO |
| 2026-04-17 | 权限与 `sys_menu.perms` 对齐；脚本合并为 `calculate_schema.sql` / `calculate_seed.sql` |

