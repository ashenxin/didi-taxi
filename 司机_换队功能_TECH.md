# 司机换队 TECH（技术设计）

本文档补齐「司机换队」实现口径：状态机、数据一致性、并发控制、幂等与错误语义。  
产品与接口分别见《司机_换队功能_产品文档.md》《司机_换队功能_接口文档（包括后台）.md》。

---

## 1. 目标与边界

- **目标**：保证“先解绑”的换队审核流程在并发场景下行为确定、数据一致，且对外错误语义稳定。
- **边界**：
  - 司机端仅调用 `driver-api`；`capacity-service` 为内部流程与数据承载。
  - 管理端审核入口为 `admin-api`（BFF），下游为 `capacity-service` 管理侧接口。

---

## 2. 核心数据与不变量

### 2.1 表与关键字段（摘要）

- `driver_team_change_request`
  - `driver_id`
  - `from_company_id` / `to_company_id`
  - `status`：`PENDING/APPROVED/REJECTED/CANCELLED`
  - `requested_at`、`requested_by`、`request_reason`
  - `reviewed_at`、`reviewed_by`、`review_reason`
- `driver`
  - `company_id`（可为空）
  - `can_accept_order`（0/1）
  - `monitor_status`（0 未听单 / 1 听单中 / 2 服务中）
  - `province_code` / `city_code`（司机档案区域）

### 2.2 不变量（必须满足）

- **I1：单活申请**：同一 `driver_id` 同时最多存在 1 条 `PENDING` 申请。
- **I2：先解绑语义**：一旦申请进入 `PENDING`，司机必须处于不可接单（`can_accept_order=0`）且无归属（`company_id=null`）。
- **I3：审核互斥**：`approve/reject/cancel` 三类动作对同一申请必须互斥，最终只允许一次“终态写入”成功。
- **I4：恢复接单**：仅 `PENDING/REJECTED` 可恢复到 `from_company_id` 并 `can_accept_order=1`，同时将 `monitor_status=0`（需要手动上线）。
- **I5：域校验**：
  - 司机端：`driverId` 必须等于 `X-User-Id`。
  - 管理端：BFF 基于 `driverCityCode` 做数据域校验，越域 404 掩蔽。

---

## 3. 状态机与动作定义

### 3.1 状态流转

- `PENDING` → `APPROVED`（管理端通过）
- `PENDING` → `REJECTED`（管理端拒绝，需原因）
- `PENDING` → `CANCELLED`（司机撤销并恢复接单）
- `REJECTED` → `CANCELLED`（司机放弃换队并恢复接单）

不支持：

- `APPROVED` → `CANCELLED`（不允许回滚）
- `CANCELLED` → 其他（终态）

### 3.2 动作的副作用（driver）

- **submit（司机提交）**
  - 新建申请：`status=PENDING`，记录 `from_company_id/to_company_id`
  - `driver.company_id = null`
  - `driver.can_accept_order = 0`
- **approve（后台通过）**
  - `request.status = APPROVED`
  - `driver.company_id = to_company_id`
  - `driver.can_accept_order = 1`
  - （可选）同步司机档案省市到目标公司（以实现为准）
- **reject（后台拒绝）**
  - `request.status = REJECTED`，写 `review_reason`
  - **不恢复**司机归属与接单资格（保持解绑+不可接单）
- **cancel（司机撤销/放弃）**
  - `request.status = CANCELLED`
  - `driver.company_id = from_company_id`
  - `driver.can_accept_order = 1`
  - `driver.monitor_status = 0`

---

## 4. 并发控制与幂等

### 4.1 CAS 更新（推荐实现）

所有状态更新采用 CAS 条件，示例语义：

- 审核通过：`UPDATE ... SET status='APPROVED' WHERE id=? AND status='PENDING'`
- 审核拒绝：`UPDATE ... SET status='REJECTED' WHERE id=? AND status='PENDING'`
- 司机撤销：
  - `PENDING` 撤销：`WHERE id=? AND status='PENDING' AND driver_id=?`
  - `REJECTED` 放弃：`WHERE id=? AND status='REJECTED' AND driver_id=?`

当 CAS 未命中时：

- 若因状态已变化（已审核/已撤销）：返回 409（或业务提示），避免把并发冲突当成 500。

### 4.2 单活申请约束

实现方式二选一（至少一种）：

- **数据库唯一约束**：如 `(driver_id, status)` 对 `PENDING` 做部分唯一（若数据库支持 partial index）
- **应用侧约束 + 行级锁**：提交时先查 `PENDING` 并对 `driver_id` 加锁（或对 driver 行 `SELECT ... FOR UPDATE`），再写入

推荐对外返回：

- 已有 `PENDING`：409（提示“已有审核中申请”）

### 4.3 幂等策略

- **cancel**：允许重复调用
  - 若已 `CANCELLED`：可直接 200 返回当前状态
  - 若已 `APPROVED`：409（不可撤销）

---

## 5. 事务一致性建议

每个动作（submit/approve/reject/cancel）建议在同一事务内完成：

- 申请单状态写入（CAS）
- 对 `driver` 的归属与接单资格写入

并发下务必保证：

- 只有一个动作能改变申请单状态（CAS），并且其对应的 `driver` 副作用与之匹配。

---

## 6. 错误语义（对外建议）

| 场景 | 建议 HTTP | 说明 |
|------|-----------|------|
| 未登录 | 401 | |
| 司机端越权（driverId 不一致） | 403 | |
| 资源不存在/越域/不属于本人 | 404 | 掩蔽 |
| 重复提交（已存在 PENDING） | 409 | |
| 状态不允许（如 APPROVED 仍 cancel） | 409 | |
| 参数校验失败 | 400 | |

---

## 7. 观测与审计（建议）

- 关键操作日志：submit/approve/reject/cancel（操作者、driverId、requestId、from/to、结果、失败原因）
- 指标：待审数量、审核耗时分布、拒绝原因 Top、并发冲突率（CAS miss）

