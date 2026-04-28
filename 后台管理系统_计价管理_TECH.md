# 后台管理系统：计价管理 TECH（技术设计）

本文档描述管理端「计价管理」的技术边界、调用链路与关键实现约束。  
接口契约见《后台管理系统_计价管理_API.md》，产品规则见《后台管理系统_计价管理_PRD.md》。

---

## 1. 系统边界与职责

### 1.1 服务划分

- **`calculate-service`**
  - 库：`calculate`
  - 表：`fare_rule`
  - 能力：规则 CRUD、费用预估（`POST /api/v1/calculate/estimate`）
- **`admin-api`（BFF）**
  - 对外前缀：`/admin/api/v1/pricing/fare-rules`
  - Feign 调用 `calculate-service` 完成规则 CRUD
  - 列表/详情的 `companyName` 通过 `CapacityClient#companyDetail` 补全
  - 不直连 `calculate` 库
- **前端 `didi-Vue`**
  - 列表页：`/pricing/fare-rules`
  - 新建/编辑页：`/pricing/fare-rules/new`、`/pricing/fare-rules/:id`
  - 公司下拉来源于运力公司档案分页能力（以实现为准）

---

## 2. 领域模型与关键约束

### 2.1 维度与字段

`fare_rule` 一行表示某「公司 + 省 + 市 + 产品线」下的一套计价参数，带有效期：

- `company_id`（指向 `capacity.company.id`）
- `company_no`（与 `capacity.company.company_no` 一致，用于审计/展示）
- `province_code` / `city_code`（必须与公司档案一致）
- `product_code`（如 `ECONOMY` / `COMFORT`）
- `effective_from` / `effective_to`（`effective_to` 为空表示长期有效）
- `is_deleted`（逻辑删除）

### 2.2 有效期重叠校验（calculate）

在同一 `company_id + province + city + product_code` 下，`is_deleted = 0` 的多条规则：

- 将 `effective_to = null` 视为无穷远
- 任意两条规则的有效期区间不得重叠

创建/更新冲突由 `calculate-service` 拒绝，错误呈现（HTTP/`code`）以全局异常处理器为准。

---

## 3. 数据权限（实现口径）

见《后台管理系统_权限与接口文档.md》§4.7。

- 列表查询：请求省/市与 JWT 数据域合并；越权筛选项 403
- 读写 body：省/市锁定在账号域内
- 资源跨域：对外统一 404（掩蔽）

---

## 4. BFF 聚合与校验策略

### 4.1 `companyName` 补全

- `AdminFareRuleVO.companyName` 不存于 `fare_rule` 表，由 `admin-api` 通过 `CapacityClient#companyDetail` 补全
- 批量列表若存在性能问题，后续可考虑批量接口或本地缓存（非首期）

### 4.2 保存前公司一致性校验

管理端新增/编辑规则时：

- 通过 `AdminDataScope` 约束/校验请求中的省/市
- BFF 拉取公司详情，校验「规则省/市」与「公司档案省/市」一致
- 写入/覆盖 `company_no` 后转发给 `calculate-service`

---

## 5. 调用链路（参考）

### 5.1 列表

`didi-Vue` → `admin-api GET /admin/api/v1/pricing/fare-rules`  
→ `calculate-service GET /api/v1/fare-rules`  
→（按需）`capacity-service companyDetail` 补全公司名

### 5.2 详情

`didi-Vue` → `admin-api GET /admin/api/v1/pricing/fare-rules/{id}`  
→ `calculate-service GET /api/v1/fare-rules/{id}`  
→ `capacity-service companyDetail` 补全公司名

### 5.3 新建/编辑/删除

`didi-Vue` → `admin-api POST/PUT/DELETE ...`  
→（保存前校验公司/域）→ `calculate-service POST/PUT/DELETE ...`

---

## 6. 数据脚本与测试一致性

脚本目录：`calculate/src/main/resources/sql/`

- `calculate_schema.sql`：建表/索引（含 `idx_fare_rule_company_scope`）
- `calculate_seed.sql`：演示数据（公司 id 与 `capacity_seed.sql` 对齐）

测试 H2：`calculate/src/test/resources/schema-test.sql` 中 `fare_rule` 需与线上结构一致。

