# 后台管理系统：计价管理 TECH（技术设计）

本文档描述管理端「计价管理」的技术边界、调用链路与关键实现约束。  
接口契约见《后台管理系统_计价管理_API.md》，产品规则见《后台管理系统_计价管理_PRD.md》。

---

## 1. 系统边界与职责

### 1.1 服务划分

- **`calculate-service`**
  - 库：`calculate`
  - 表：`fare_rule`、车队营销优惠券相关表（详见《二期功能/车队营销优惠券_SQL.md》）
  - 能力：规则 CRUD、费用预估（`POST /api/v1/calculate/estimate`）、优惠券模板查询/计算/用券
- **`admin-api`（BFF）**
  - 对外前缀：`/admin/api/v1/pricing/fare-rules`
  - Feign 调用 `calculate-service` 完成规则 CRUD
  - 从计价规则详情页聚合同范围优惠券方案
  - 列表/详情的 `companyName` 通过 `CapacityClient#companyDetail` 补全
  - 不直连 `calculate` 库
- **前端 `didi-Vue`**
  - 列表页：`/pricing/fare-rules`
  - 新建/编辑页：`/pricing/fare-rules/new`、`/pricing/fare-rules/:id`
  - 优惠券方案入口：从 `/pricing/fare-rules/:id` 进入
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

### 2.3 与优惠券方案的解耦关系

`fare_rule` 只保存基础计价规则，不保存优惠券字段。

后台从某条计价规则详情进入优惠券方案时：

1. `admin-api` 获取 `fare_rule` 详情。
2. 校验当前登录用户对该规则有数据域可见权限。
3. 使用 `company_id + city_code + product_code` 查询 `coupon_template`。
4. 返回同范围优惠券模板列表、统计与操作权限。

不做：

- 不新增 `fare_rule.enable_coupon`。
- 不新增 `fare_rule.coupon_*` 字段。
- 不在 `coupon_template` 保存 `fare_rule_id` 强绑定。

原因：

- 计价规则、营销优惠、结算分配职责不同。
- 同一车队同一城市产品线的优惠活动可与多个历史计价版本并行解释。
- 历史订单最终以结算快照解释金额，而不是反查当前 `fare_rule` 或模板。

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

### 5.4 查看优惠券方案

`didi-Vue` → `admin-api GET /admin/api/v1/pricing/fare-rules/{id}/coupons`  
→ `calculate-service` 查询 `coupon_template`  
→ 返回同范围优惠券模板列表。

超管写操作：

`didi-Vue` → `admin-api POST/PUT/POST publish/offline /admin/api/v1/coupons/templates...`  
→ `admin-api` 校验超管角色与计价规则数据域  
→ `calculate-service` 写入或更新优惠券模板。

---

## 6. 数据脚本与测试一致性

脚本目录：`calculate/src/main/resources/sql/`

- `calculate_schema.sql`：建表/索引（含 `idx_fare_rule_company_scope`）
- `calculate_seed.sql`：演示数据（公司 id 与 `capacity_seed.sql` 对齐）
- 车队营销优惠券目标 SQL：见 `二期功能/车队营销优惠券_SQL.md`，后续应同步落入 calculate/order 的正式迁移脚本。

测试 H2：`calculate/src/test/resources/schema-test.sql` 中 `fare_rule` 需与线上结构一致。

## 7. 资金口径注意

两段式异步派单后，预估阶段候选司机 `company_id` 可能与最终承运司机 `company_id` 不一致。计价管理后台只负责配置规则；支付前确认价、优惠券可用性、锁券、结算快照必须以后续订单最终 `trip_order.company_id` 为准。详见《二期功能/车队营销优惠券_TECH.md》。
