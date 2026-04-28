# 司机端登录/注册 TECH（技术设计）

本文档描述司机端登录/注册的系统边界、安全约束、JWT 与 `token_version`、Redis 风控设计，以及与接单/听单/WS 的衔接口径。  
接口契约与产品口径分别见《司机端_登录注册_API.md》《司机端_登录注册_PRD.md》。

---

## 1. 架构边界

- **Gateway**：统一入口，转发 `/driver/**` 至 BFF；校验 JWT（签名/exp，启用时校验 aud）。
- **BFF**：`driver-api`，对外暴露 `/driver/api/v1/**`；负责签发 JWT，并在应用侧校验 `tv`。
- **Core**：`capacity-service`，承载司机档案（`capacity.driver`）与接单资格主数据；建议承载 OTP 校验、频控与验密能力，BFF 负责编排与签 token。

---

## 2. 数据模型（MySQL）

### 2.1 `capacity.driver`（扩展口径）

- `phone`：手机号
- `password_hash`：BCrypt 摘要，可空（表示未设密）
- `audit_status`：审核状态快照（用于接单拦截，不用于限制登录）
- `can_accept_order`：接单开关（业务限制）
- `monitor_status`：听单状态

审核状态取值以代码为准；文档中出现过 0～4 的建议枚举，当前运力配置文档口径为 0～3（需与实现对齐）。

### 2.2 `driver_audit_record`（审核流水，若已落库）

- 记录每次审核的结果与原因；主表仅保留快照与最新记录指针（可选）。

---

## 3. JWT 约定（司机端 Access Token）

### 3.1 Claims

- `sub`：`driver.id`（字符串）
- `aud`：`driver-bff`
- `exp`：8 小时自然过期
- `tv`：token version（与 Redis 当前值一致才有效）

### 3.2 密钥隔离

- 司机端使用独立密钥（示例 `JWT_SECRET_DRIVER`）
- 网关验签使用 `gateway.jwt.secret-driver`，必须与 BFF 一致
- 建议与 app/admin 端密钥不同，避免跨端 token 复用

---

## 4. 单会话机制（`token_version` / `tv`）

### 4.1 目标语义

- 同一 `driverId` 全局仅一个有效 token
- **登录成功**与**登出**都会使旧 token 立即失效

### 4.2 Redis 存储

- 键：`driver:tv:{driverId}`
- 操作：
  - 登录成功：`INCR` 并把新值写入 JWT `tv`
  - 登出：`INCR`，使旧 token 的 `tv` 立刻落后

### 4.3 校验位置

- 网关：验签/exp/aud（不校验 tv）
- `driver-api`：在鉴权过滤器中比对 `tv` 与 Redis，不一致返回 401（登录失效）

---

## 5. Redis（OTP 与风控）

建议司机端独立前缀，避免与乘客端互相影响。

- OTP：`driver:otp:{phone}`（TTL=300s）
- 短信发送间隔：`driver:sms:gap:{phone}`（TTL=60s）
- 短信发送日计数：`driver:sms:daily:{yyyyMMdd}:{phone}`（TTL≈2d）
- 登录失败计数：`driver:login:fail:{yyyyMMdd}:{phone}`（TTL≈2d，密码+短信合并）
- 当日封禁：`driver:login:ban:{yyyyMMdd}:{phone}`（TTL≈2d）

---

## 6. 接单/听单/WS 的衔接（安全口径）

### 6.1 身份绑定

- 网关验签通过后注入 `X-User-Id = JWT.sub`
- 司机端业务写接口必须以 `X-User-Id` 为准，忽略或强校验客户端传入的 `driverId`，避免伪造

### 6.2 接单资格校验

- **允许登录**不代表允许接单
- 听单/接单等动作需满足（示例）：
  - `audit_status == APPROVED` 且 `can_accept_order == 1`
  - 否则返回 403，并引导补件/申诉

### 6.3 WebSocket

- 若后续启用 WS + presence，建议握手同 JWT 体系
- `tv` 变化（登出/异地登录）后旧连接应断开或心跳失败，从而同步离线

---

## 7. 错误码建议

- `400` 参数错误（手机号/验证码格式等）
- `401` 登录失败/登录失效（密码错、验证码错/过期、缺 token、tv 不一致）
- `403` 禁止接单（用于业务接口，不用于禁止登录）
- `409` 业务冲突（可选：强区分注册/登录时的已注册）
- `429` 频控/次数限制/当日封禁
- `5xx` 下游异常

