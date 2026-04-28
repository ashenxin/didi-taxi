# 后台管理系统：运力配置 TECH（技术设计）

本文档描述管理端「运力配置/换队审核」的技术边界、关键模型与实现约束。  
接口契约见《后台管理系统_运力配置_API.md》，产品口径见《后台管理系统_运力配置_PRD.md》。

---

## 1. 系统边界与职责

- **`capacity-service`**
  - 领域：公司 `Company`、司机 `Driver`、车辆 `Car`、换队申请 `DriverTeamChangeRequest`
  - 数据：`capacity` 库
  - 能力：公司/司机/车辆查询与公司 CRUD；换队审核接口
- **`admin-api`（BFF）**
  - 对外：`/admin/api/v1/capacity`、`/admin/api/v1/capacity/team-change-requests`
  - 通过 Feign 调用 `capacity-service`
  - 执行 JWT 与数据域（`AdminDataScope`）校验；对跨域资源掩蔽为 404
  - 不直连运力库
- **前端 `didi-Vue`**
  - 路由与菜单以 `sys_menu` 为准
  - 列表尽量展示可读名称字段，减少裸 ID

---

## 2. 领域模型与关键约束

### 2.1 公司（Company）

- **一行 = 公司 + 车队（承运单元）**
- `id`：技术主键（司机归属与换队目标均引用 `company.id`）
- `team_id`：车队业务编码，全库唯一（`uk_company_team_id`）
  - 若创建时未填写，由服务端按 `MAX(team_id)+1`（起点 2001）生成
  - 已删除行的 `team_id` 不回收
- 同一业务公司可复用 `company_no / company_name`，用 `team` 区分不同承运单元

写操作限制：

- 更新仅允许修改 `companyName`、`team`
- 删除为逻辑删除；若存在 `is_deleted=0` 且 `company_id` 指向该行的司机，拒绝删除

### 2.2 司机（Driver）

- `companyId`：归属公司（换队审核期可为空）
- `cityCode`：数据域与换队筛选关键字段
- `provinceCode` / `provinceName`：用于省筛选与展示（兼容历史仅存市档案的数据）
- `auditStatus`：0 待完善、1 审核中、2 通过、3 驳回/需补件；不再使用 4

分页筛选兼容策略（capacity）：

- 省筛选优先 `province_code = provinceCode`（6 位国标省码）
- 若历史行 `province_code` 为空：退回 `city_code LIKE 省码前两位%`
- 市筛选：`city_code = cityCode`

### 2.3 车辆（Car）

- 车辆归属一名司机
- 除「司机名下车辆」外，提供独立车辆列表用于检索排障
- 车辆省/市筛选按车辆 `city_code` 过滤（省筛为市码前缀匹配）

### 2.4 换队申请（DriverTeamChangeRequest）

- 状态：`PENDING / APPROVED / REJECTED` 等（以实现枚举为准）
- 列表/详情 VO 含 `driverCityCode`，供 BFF 域校验
- 审核动作需并发保护（CAS）：仅 `PENDING` 可被审核更新，避免重复审核

---

## 3. 换队审核策略（先解绑方案）

- 提交后：解除原归属、不可接单（如 `can_accept_order=0`，以字段为准）
- 通过：归属目标公司并恢复可接单
- 拒绝：不回滚旧公司；司机仍无归属且不可接单，需重新申请

接单链路与司机端需校验：存在有效归属且允许接单，与换队状态保持一致（由各服务实现保障）。

---

## 4. 数据权限（实现口径）

见《后台管理系统_权限与接口文档.md》§4.7。

- 公司/司机/车辆/换队：均需执行 `AdminDataScope` 裁剪与校验
- 越权筛选项：403
- 跨域资源：对外统一 404（掩蔽）
- 换队：列表在 `capacity-service` 侧按司机档案过滤；详情/审核前 BFF 校验 `driverCityCode`

---

## 5. Feign 与调用链路（参考）

- `admin-api` → `capacity-service`
  - 公司：分页/详情/创建/更新/删除
  - 司机：分页/详情、司机车辆
  - 车辆：分页/详情
  - 换队：分页/详情/通过/拒绝、待审数

---

## 6. 库表与脚本

脚本目录：`capacity/src/main/resources/sql/`

- `capacity_schema.sql`：全量建表（含 `driver.province_code/province_name`、索引 `idx_driver_province_code`）
- `capacity_seed.sql`：演示数据（TRUNCATE 后写入；与订单/计价联调 id 对齐）

存量库若存在 `audit_status = 4` 等历史值，可按口径手工迁移为 `3`（驳回/需补件）。

