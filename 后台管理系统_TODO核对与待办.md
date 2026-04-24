# 后台管理系统 TODO 核对报告

对照 **本仓库 `didi-taxi`**（`admin-api`、`passenger`、`order`、`capacity`、`calculate` 等）与配套设计/接口文档，整理：**已实现要点**、**仍存在的差距**、**文档索引**。  
前端 **`didi-Vue`** 若不在本仓库，以下凡写「前端」处请在你本地前端工程内再核一眼。

> 核对日期：**2026-04-24**（以当前提交为准；本地未提交改动请自行复核）。

---

## 〇、相关文档索引（替代旧版长篇 TODO）

| 域 | 设计 | 接口契约 |
|----|------|----------|
| 权限 / 登录 / `sys_*` | 《后台管理系统_权限清单与鉴权设计.md》 | 《后台管理系统_权限与接口文档.md》 |
| 订单 | 《后台管理系统_订单管理_设计.md》 | 《后台管理系统_订单管理_接口文档.md》 |
| 运力 / 换队 | 《后台管理系统_运力配置_设计.md》 | 《后台管理系统_运力配置_接口文档.md》 |

订单 / 运力原「长篇 TODO」已废止，请以 **§〇 索引** 中 `_设计.md` / `_接口文档.md` 为准；**本文件**承担「实现 vs 规划」差异快照。

---

## 一、订单管理

### 1.1 已覆盖（与设计与接口文档一致）

| 项 | 说明 |
|----|------|
| BFF | `GET /admin/api/v1/orders`、`GET /admin/api/v1/orders/{orderNo}` |
| 筛选 | 订单号、手机、省市、状态、时间区间、分页；**省/市与 JWT 域合并**（`AdminDataScope`） |
| `orderNo` vs `phone` | 有 `orderNo` 时不走手机换 `passengerId`；手机无乘客 → **空列表** |
| 列表防 N+1 | 列表行清空 `passengerId`/`passengerPhone`；详情再取乘客手机 |
| 下游 | `order` 分页/详情/events；`passenger` by-phone / by-id |
| 事件顺序 | `order-service` 侧按 `occurredAt`（及 id）升序；与前端升序展示约定一致 |

### 1.2 仍为 MVP+ / 未做（与设计文档「非目标」一致）

- 退款对账、人工改单/派单、导出、手机号脱敏、结构化审计等。
- TODO 中曾写的「全量关键时间线、`cancelBy`、事件 payload 折叠」等排障增强：**非本期硬性范围**。

### 1.3 前端（若在独立仓）

- 路由 `/orders`、`/orders/:orderNo`、错误处理与 `http.js` 行为需在 **didi-Vue** 内确认；本报告以 **BFF 与核心服务** 为准。

---

## 二、运力与换队

### 2.1 已覆盖

| 项 | 说明 |
|----|------|
| BFF 路径 | `/admin/api/v1/capacity/companies`、`.../drivers`、`.../drivers/{id}/cars`；换队 **`/admin/api/v1/capacity/team-change-requests`**（含 `pending-count`、列表、详情、approve/reject） |
| capacity 直连 | 公司分页、司机分页（含 **`provinceCode`/`cityCode`**、**`companyId`**、`online` 等）、**`GET /api/v1/drivers/{id}`**、司机车辆分页；管理端换队 **`/api/v1/admin/driver-team-change-requests*`** |
| 数据域 | 公司/司机/司机车辆/换队列表与待审数/详情/审核均走 **`AdminDataScope`**；换队 VO 含 **`driverCityCode`**；详见《权限与接口文档》**§4.7** |
| 计价 BFF | **`/admin/api/v1/pricing/fare-rules`** CRUD（`AdminPricingController` + `CalculateClient`）；规则读写同样受省/市域约束 |
| 鉴权（后端） | **`POST /admin/api/v1/auth/login`**、`GET /admin/api/v1/auth/me`、`GET /admin/api/v1/auth/menus`；JWT + `passenger` `sys_*` 校验（见权限接口文档） |
| 独立车队管理页 | **不做**（以公司列表表达组织维度），与《运力配置_设计》一致 |

### 2.2 仍存在的差距 / 技术债

| 优先级 | 项 | 说明 |
|--------|-----|------|
| ~~**高**~~ | ~~**换队申请提交（POST）**~~ | ✅ **已完成**：`capacity` 已实现 app 侧提交/查询/撤销接口（`AppDriverTeamChangeStubController` + `DriverTeamChangeService` 真实落库与解绑/恢复逻辑，不再 501）。`driver-api` 已对外提供司机端换队接口（搜索/提交/查询/撤销），并补充 `GET /driver/api/v1/team-change/belonging` 用于在“提交后解绑（companyId=null）”场景仍能展示**原属车队**（从申请单 `fromCompanyId` 回填）。对应 H5（`didi-driver-h5`）已接入该接口展示当前归属。 |
| ~~**中**~~ | ~~**独立车辆列表 BFF**~~ | ✅ **已完成**：新增 **`GET /admin/api/v1/capacity/cars`**（按车牌/司机/公司/城市/品牌名称/运力类型筛选）与 **`GET /admin/api/v1/capacity/cars/{id}`** 详情；省/市域按登录用户 `AdminDataScope` 约束。对应 capacity 直连补齐 **`GET /api/v1/cars`**（支持同筛选）与 **`GET /api/v1/cars/{id}`**。 |
| ~~**中**~~ | ~~**`reviewedBy`**~~ | ✅ **已完成**：换队审核不再依赖 query 入参；`admin-api` 从登录态写入操作者 **`sys_user.id`**（`AdminLoginUser.userId`）并透传至 capacity 落库（`driver_team_change_request.reviewed_by`）。 |
| **低** | **修改密码等 auth 扩展** | 权限文档中的 **`PUT .../auth/password`** 等是否在 BFF 接满，可与《权限与接口文档》逐条对照。 |
| **低** | **审核结构化审计日志** | 设计文档建议字段；若仅业务/控制台日志，记为 **可观测性待加强**。 |
| **低** | **通用前端组件** | SearchForm / DataTable 等抽象：页面内联实现为主则视为 **技术债**（前端仓核对）。 |

### 2.3 前端（若在独立仓）

- **登录页**、**计价管理页面**是否与 BFF 已对齐：本仓库无法直接确认；若仅后端就绪而菜单仍「待开发」，在排期上标为 **前端待接**。

---

## 三、全站鉴权与数据域（摘要）

- **RBAC、JWT、`token_version`、菜单树**：以 `passenger` `sys_*` + `admin-api` 为准（权限两份文档）。
- **订单 / 计价 / 运力 / 换队**：列表与写操作 **`AdminDataScope`** 已接；**403**（越界筛选）与 **404**（跨域资源掩蔽）语义见 §4.7。
- **后台员工（如 SUPER / 省管）CRUD**：`admin-api` **`/admin/api/v1/system/admin-users`** 等与 passenger 内部接口联动，细节见权限接口文档。

---

## 四、建议排期（按收益）

1. **换队申请 POST**：开放 app 或内部创建路径 + 必要时 BFF 透传，消除 **501**。  
2. **前端**：登录/菜单/计价/运力页与现 BFF **对齐验收**（若在独立仓库，单列一轮联调）。  
3. **按需：审计**：在业务/审计表中补齐结构化日志（操作者 id、资源 id、动作、结果、时间等）。  
4. **按需**：修改密码接口、其它 `GET .../{id}` 详情类接口（详见《运力配置_设计》「按需再补」）。

---

## 五、使用说明

- **本文件**为「代码与设计文档」对照快照；迭代后更新 **§〇 文档索引**与 **§二 差距表**即可。  
- **产品设计**（是否做导出、脱敏等）以各 **`_设计.md`** 为准；**路径与参数**以 **`_接口文档.md` + 源码** 为准。
