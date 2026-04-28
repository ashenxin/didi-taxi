# 司机端登录/注册 API（接口契约）

本文档用于前后端联调与自测，定义司机端登录/注册相关对外接口契约（`driver-api` 对外）。  
产品口径与技术实现分别见《司机端_登录注册_PRD.md》《司机端_登录注册_TECH.md》。

- 统一网关入口：`gateway` `http://127.0.0.1:8080`
- BFF 服务：`driver-api`（路由前缀 `/driver/**`）
- 统一响应：`{ code, msg, data }`

---

## 1. 通用约定

### 1.1 JWT 约定（司机端）

- `sub`：司机 ID（`driver.id`，字符串）
- `aud`：`driver-bff`（必须；网关开启 aud 校验）
- `exp`：自然过期 **8 小时**；响应字段 `expiresIn=28800`（秒）
- `tv`：token version（与 Redis `driver:tv:{driverId}` 当前值一致才有效）
  - 同一司机全局仅一个有效会话：新登录或登出都会使旧 token 失效

### 1.2 网关 Public 白名单（无需 token）

以下接口均为 `POST`，建议网关放行：

- `/driver/api/v1/auth/sms/send`
- `/driver/api/v1/auth/register-sms`
- `/driver/api/v1/auth/register-password`
- `/driver/api/v1/auth/login-sms`
- `/driver/api/v1/auth/login-password`

需登录（不在白名单）：例如 `POST /driver/api/v1/auth/logout`。

---

## 2. 发送短信验证码

- **POST** `/driver/api/v1/auth/sms/send`

请求：

```json
{ "phone": "13900000000" }
```

成功：

```json
{ "code": 200, "msg": "success", "data": null }
```

常见错误：

- `400` 参数非法（手机号格式）
- `429` 发送过于频繁/今日上限

---

## 3. 短信注册（OTP 注册）

- **POST** `/driver/api/v1/auth/register-sms`

请求：

```json
{ "phone": "13900000000", "code": "123456" }
```

成功：

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "accessToken": "<jwt>",
    "tokenType": "Bearer",
    "expiresIn": 28800,
    "driver": {
      "id": 80001,
      "phone": "13900000000",
      "auditStatus": 0
    }
  }
}
```

常见错误：

- `401` 验证码错误或过期
- `409` 已注册（若采用“强区分注册/登录”策略）

---

## 4. 密码注册（OTP 校验 + 设置密码）

> 密码注册必须绑定短信验证码，避免仅凭手机号“占号”。

- **POST** `/driver/api/v1/auth/register-password`

请求：

```json
{ "phone": "13900000000", "code": "123456", "password": "plaintext" }
```

成功：同短信注册（返回 token）。

常见错误：

- `401` 验证码错误或过期
- `409` 已注册（若采用“强区分注册/登录”策略）

---

## 5. 短信登录（OTP 登录）

> 推荐：登录不隐式创建司机；未注册手机号提示先注册。

- **POST** `/driver/api/v1/auth/login-sms`

请求：

```json
{ "phone": "13900000000", "code": "123456" }
```

成功：返回 token（结构同上）。

未注册提示（实现可选其一，建议统一口径）：

```json
{ "code": 404, "msg": "该手机号未注册，请先注册", "data": null }
```

---

## 6. 密码登录

- **POST** `/driver/api/v1/auth/login-password`

请求：

```json
{ "phone": "13900000000", "password": "plaintext" }
```

成功：返回 token（结构同上）。

常见错误：

- `401` 手机号或密码错误
- `401` 未设置密码：`未设置密码，请使用验证码登录或先设置密码`

---

## 7. 退出登录（需鉴权）

> `driver-api` 负责 JWT 作废；`capacity` 内网接口不负责 JWT 作废。

- **POST** `/driver/api/v1/auth/logout`
- Header：`Authorization: Bearer <accessToken>`

成功：

```json
{ "code": 200, "msg": "success", "data": null }
```

服务端语义（摘要）：

- 从 JWT 解析 `sub`（driverId），对 Redis `driver:tv:{driverId}` 执行 `INCR`
- 后续请求若 JWT 内 `tv` 与 Redis 不一致，视为登录失效（401）

---

## 8. 登录后调用示例（需要 Authorization）

示例（工程已有接口形态）：

- **GET** `/driver/api/v1/orders/assigned?driverId=80001`

```bash
curl "http://127.0.0.1:8080/driver/api/v1/orders/assigned?driverId=80001" \
  -H "Authorization: Bearer <jwt>"
```

安全建议：业务接口应以 `X-User-Id`（JWT sub）为准，忽略或强校验请求中的 `driverId`，防止伪造。

---

## 9. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-04-13 | 补充登出接口与 `token_version`（`driver:tv:*`）语义 |
| 2026-04-11 | Access Token 自然过期调整为 8 小时（28800 秒） |

