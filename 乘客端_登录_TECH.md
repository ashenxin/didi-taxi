# 乘客端登录 TECH（技术设计）

本文档描述乘客端登录的系统边界、安全约束、JWT 约定、Redis 设计与网关放行策略。  
接口契约与产品口径分别见《乘客端_登录_API.md》《乘客端_登录_PRD.md》。

---

## 1. 架构边界

- **Gateway**：统一入口（端口 8080），转发 `/app/**` 至 BFF；校验 Bearer JWT（签名/exp，启用时校验 aud）。
- **BFF**：`passenger-api`，对外暴露 `/app/api/v1/**`；负责签发 JWT（建议）。
- **Core**：`passenger` 服务，访问 `passenger` 数据库；建议承载验密、OTP 校验与频控等核心逻辑，BFF 只做编排与签 token。

---

## 2. 数据模型（MySQL）

### 2.1 `passenger.customer`（已存在）

- `id`：主键
- `phone`：登录主键（未删除记录唯一）
- `password_hash`：可空（支持仅短信登录/短信后设密）
- `status`：0 正常；非 0 冻结/限制等（以实现为准）
- `is_deleted`：逻辑删除

---

## 3. JWT 约定（Access Token）

### 3.1 Claims

- `sub`：`customer.id`（字符串）
- `exp`：过期时间（必须）
- `iat`：签发时间（建议）
- `aud`：`app-bff`（建议启用网关 aud 校验）

### 3.2 密钥隔离

- 乘客端建议使用独立密钥（示例环境变量 `JWT_SECRET_APP`）
- 网关验签使用 `gateway.jwt.secret-app`，必须与 BFF 一致
- 建议与 admin/driver 端密钥不同，避免跨端 token 复用风险

### 3.3 过期策略与登出失效

- 7 天（604800 秒）
- JWT 含 `tv`，Redis 键 `passenger:tv:{customerId}`；登录、**`POST /app/api/v1/auth/logout`** 均递增 `tv`，使旧 token 在 `passenger-api` 侧立即失效（与司机端 `driver:tv:*` 思路一致）
- 客户端仍应在登出成功后删除本机 token；详见《乘客端_登录_API.md》§4

---

## 4. 网关白名单与 aud 防御纵深

### 4.1 白名单（无需 token）

仅放行 3 个 POST：

- `/app/api/v1/auth/login-password`
- `/app/api/v1/auth/login-sms`
- `/app/api/v1/auth/sms/send`

### 4.2 aud 校验（建议开启）

用于防止跨端误用 token：

- `/app/**` 仅接受 `aud=app-bff`
- `/admin/**` 仅接受 `aud=admin-bff`
- `/driver/**` 仅接受 `aud=driver-bff`

---

## 5. Redis 设计（OTP 与频控）

### 5.1 Key 约定（建议）

- OTP：`app:otp:{phone}` → `code`（TTL=300s）
- 发送间隔锁：`app:sms:gap:{phone}` → `1`（TTL=60s）
- 每日发送计数：`app:sms:daily:{phone}:{yyyy-MM-dd}` → `count`（TTL≈2d）
- 登录失败计数：`app:login:fail:{phone}:{yyyy-MM-dd}` → `count`（TTL≈2d，密码+验证码合并）
- 登录禁用标记：`app:login:ban:{phone}:{yyyy-MM-dd}` → `1`（TTL≈2d）

### 5.2 规则

- 发送间隔：≥ 60s
- 发送日上限：≤ 5 次（可配置）
- OTP：6 位数字；TTL 300s；校验成功后删除
- 登录失败限制：当日失败次数上限 5 次；达到上限后设置 ban 标记并返回 429

---

## 6. 安全与身份衔接（必须对齐）

### 6.1 身份来源

- 网关验签通过后注入 `X-User-Id = JWT.sub`
- 后续业务接口涉及乘客身份必须从 `X-User-Id` 获取 `customerId`

### 6.2 过渡期兼容（若存在 passengerId 入参）

若现有接口仍接收 `passengerId/customerId`：

- 必须校验其与 `X-User-Id` 一致，不一致返回 401/403
- 逐步移除该入参，避免越权风险

---

## 7. 错误码与提示语建议

- `400`：参数错误（手机号格式、缺参）
- `401`：未授权（密码错、验证码错/过期、缺 token）
- `403`：冻结
- `429`：频控（短信/登录失败上限）
- `5xx`：下游异常（BFF 调 Core 或短信服务失败）

---

## 8. 二期演进（非 MVP）

- 改密/踢下线：`token_version`（JWT 带 `tv`，服务端校验）
- Refresh Token：短 access + 长 refresh，支持轮换与退出
- 更强风控：设备指纹、IP 限制、黑名单、图形/滑块验证码
- OAuth2：新增绑定表、回调与账号合并策略；OAuth 成功后仍签发自建 JWT

