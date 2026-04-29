# 乘客端登录 API（接口契约）

本文档用于前后端联调与自测，定义乘客端登录相关对外接口契约。  
产品口径与技术实现分别见《乘客端_登录_PRD.md》《乘客端_登录_TECH.md》。

对外统一经 `gateway` 访问（本地默认 `http://127.0.0.1:8080`），统一前缀：`/app/api/v1/auth`。

---

## 0. 通用约定

### 0.1 请求头

- JSON：`Content-Type: application/json`
- 鉴权（除白名单外）：`Authorization: Bearer <accessToken>`

### 0.2 响应结构

统一 `ResponseVo<T>`：

```json
{ "code": 200, "msg": "success", "data": {} }
```

### 0.3 网关白名单（无需 token）

以下 3 个接口无需 `Authorization`：

- `POST /app/api/v1/auth/login-password`
- `POST /app/api/v1/auth/login-sms`
- `POST /app/api/v1/auth/sms/send`

其余 `/app/**` 均需要 token（无“无登录试玩”需求）。

### 0.4 JWT（Access Token）约定

- 签名：HMAC（与网关共享密钥）
- `sub`：`customerId`（字符串）
- `aud`：`app-bff`（网关开启 `aud` 校验时需要）
- `tv`：token 版本（数字）；`passenger-api` 登录时递增，`POST /app/api/v1/auth/logout` 再递增，使旧 JWT 立即失效；请求业务接口时须与 Redis `passenger:tv:{customerId}` 一致
- 过期：7 天（`expiresIn=604800`）

后续业务接口涉及乘客身份统一从网关注入头 `X-User-Id`（即 JWT `sub`）获取，不信任客户端传 `passengerId/customerId`。

---

## 1. 发送短信验证码

### 1.1 接口

- **POST** `/app/api/v1/auth/sms/send`

### 1.2 请求

```json
{
  "phone": "13800138000"
}
```

字段说明：

- `phone`：大陆手机号（建议 `^1\\d{10}$`）

### 1.3 响应

成功：

```json
{ "code": 200, "msg": "success", "data": null }
```

### 1.4 错误码与文案

- `400`：参数错误（如手机号格式不正确）
- `429`：频控/上限
  - 发送过于频繁：`发送过于频繁，请稍后再试`
  - 今日发送次数上限（自然日 5 次）：`今日验证码发送次数已达上限，请明天再试`

---

## 2. 验证码登录（登录即注册）

### 2.1 接口

- **POST** `/app/api/v1/auth/login-sms`

### 2.2 请求

```json
{
  "phone": "13800138000",
  "code": "123456"
}
```

### 2.3 响应

成功：

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "accessToken": "<jwt>",
    "tokenType": "Bearer",
    "expiresIn": 604800,
    "customer": {
      "id": 10001,
      "phone": "13800138000",
      "nickname": "乘客A"
    }
  }
}
```

字段说明：

- `accessToken`：JWT access token
- `tokenType`：固定 `Bearer`
- `expiresIn`：过期秒数（7 天）
- `customer.id`：`customerId`（同时写入 JWT `sub`）
- `customer.phone`：手机号（仅展示，不用于鉴权）
- `customer.nickname`：昵称（可空）

### 2.4 错误码与文案

- `401`：验证码错误或已过期：`验证码错误或已过期`
- `403`：账号冻结：`账号已冻结，请联系客服`
- `429`：登录失败次数过多（自然日 5 次）：`登录失败次数过多，请明天再试`

---

## 3. 密码登录

### 3.1 接口

- **POST** `/app/api/v1/auth/login-password`

### 3.2 请求

```json
{
  "phone": "13800138000",
  "password": "plaintext"
}
```

字段说明：

- `phone`：手机号
- `password`：明文密码（仅 HTTPS 传输；服务端存 BCrypt 摘要）

### 3.3 响应

成功：同「验证码登录」响应（返回 token + customer）。

### 3.4 错误码与文案

- `401`
  - 手机号或密码错误：`手机号或密码错误`
  - 该手机号未设置密码（`password_hash` 为空）：`请使用验证码登录`
- `403`：账号冻结：`账号已冻结，请联系客服`
- `429`：登录失败次数过多（自然日 5 次）：`登录失败次数过多，请明天再试`

---

## 4. 退出登录

### 4.1 接口

- **POST** `/app/api/v1/auth/logout`
- **鉴权**：须 `Authorization: Bearer <accessToken>`（不在白名单）

### 4.2 请求体

无，或 `{}`。

### 4.3 行为说明（与 MVP PRD §5.6 一致）

- 登出成功后旧 token 不可用（`tv` 递增）。
- 若乘客存在「司机到达前」在途单（订单状态 CREATED / ASSIGNED / PENDING_DRIVER_CONFIRM / ACCEPTED）：服务端代为取消，取消原因「乘客退出登录」。
- 若存在已到达或行程中订单：不代为取消；响应 `data.hint` 给出说明，仍完成登出。

### 4.4 响应

成功示例：

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "hint": "已为您取消进行中的订单（退出登录）。"
  }
}
```

`hint` 可空；无在途单时通常无提示。

---

## 5. 自测用例（示例）

### 5.1 发送验证码

```bash
curl -X POST 'http://127.0.0.1:8080/app/api/v1/auth/sms/send' \
  -H 'Content-Type: application/json' \
  -d '{"phone":"13800138000"}'
```

### 5.2 验证码登录

```bash
curl -X POST 'http://127.0.0.1:8080/app/api/v1/auth/login-sms' \
  -H 'Content-Type: application/json' \
  -d '{"phone":"13800138000","code":"123456"}'
```

### 5.3 密码登录

```bash
curl -X POST 'http://127.0.0.1:8080/app/api/v1/auth/login-password' \
  -H 'Content-Type: application/json' \
  -d '{"phone":"13800138000","password":"plaintext"}'
```

### 5.4 退出登录（需先登录拿 token）

```bash
curl -X POST 'http://127.0.0.1:8080/app/api/v1/auth/logout' \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <accessToken>' \
  -d '{}'
```

---

## 6. 备注（与后续业务接口的衔接）

- 网关验签通过后会注入 `X-User-Id`（值为 JWT `sub`）。
- 后续订单/行程/资料等接口涉及乘客身份统一从 `X-User-Id` 获取 `customerId`，不再让客户端提交 `passengerId/customerId`。

