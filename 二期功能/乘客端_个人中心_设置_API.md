# 乘客端个人中心「设置」二期 API

> 本文档描述「设置」页面所需接口、请求参数、返回参数和约定错误码。
> 产品口径见《乘客端_个人中心_设置_PRD.md》，技术实现见《乘客端_个人中心_设置_TECH.md》，测试用例见《乘客端_个人中心_设置_TEST.md》。

---

## 0. 通用约定

### 0.1 统一返回

所有接口统一返回：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| code | number | 是 | 200 成功；400/401/403/404/409/429/500/502 等错误码 |
| msg | string | 是 | 提示信息 |
| data | object\|null | 否 | 业务数据 |

### 0.2 鉴权与身份

- 所有设置接口均通过网关访问。
- 除公开登录/发短信外，本功能接口均需要 `Authorization: Bearer <accessToken>`。
- `passenger-api` 校验 JWT 后注入 `X-User-Id`，该值对应 `customer.id`。
- 更换手机号、注销账号均以 `X-User-Id` / JWT `sub` 作为当前乘客身份，不信任前端传入的用户 ID。

### 0.3 手机号与验证码

- 手机号格式：`^1\d{10}$`。
- 更换手机号的新手机号验证码使用独立业务场景，不复用登录验证码。
- 注销账号验证码使用独立业务场景，不复用登录验证码。
- 验证码错误或过期统一返回 `401` 或业务约定错误码，文案避免泄漏过多细节。

---

## 1. 设置首页信息

### 1.1 查询当前账号设置摘要

**GET** `/app/api/v1/settings/profile`

**说明**

- 供设置页展示当前绑定手机号脱敏信息。
- 后端根据当前登录态查询 `customer.id` 对应账号。

**请求头**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| Authorization | string | 是 | `Bearer <accessToken>` |
| X-User-Id | number | 是 | 网关或 BFF 鉴权过滤器注入的当前乘客 ID |

**响应 data**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| customerId | number | 是 | 当前乘客 ID |
| maskedPhone | string | 是 | 当前绑定手机号脱敏展示，如 `138****8000` |
| status | number | 是 | 账号状态 |
| deleted | boolean | 是 | 是否已注销；正常登录态下应为 `false` |

**响应示例**

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "customerId": 10001,
    "maskedPhone": "138****8000",
    "status": 0,
    "deleted": false
  }
}
```

---

## 2. 更换手机号

### 2.1 发送新手机号验证码

**POST** `/app/api/v1/settings/phone-change/sms/send`

**说明**

- 给新手机号发送更换手机号验证码。
- 服务端必须校验当前登录态。
- 发送前应校验新手机号未被其他未注销账号占用。
- 不发送旧手机号验证码。

**请求头**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| Authorization | string | 是 | `Bearer <accessToken>` |
| X-User-Id | number | 是 | 当前乘客 ID |

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| newPhone | string | 是 | 新手机号 |

**响应 data**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| mockCode | string\|null | 否 | 本地 mock 短信开启时返回验证码；生产为空 |

**请求示例**

```http
POST /app/api/v1/settings/phone-change/sms/send
Authorization: Bearer eyJ...
Content-Type: application/json

{ "newPhone": "13900139000" }
```

**成功响应示例**

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "mockCode": "123456"
  }
}
```

**错误码**

| code | 场景 | 文案建议 |
|---:|---|---|
| 400 | 手机号格式错误 / 新手机号与当前手机号相同 | `手机号格式不正确` / `新手机号不能与当前手机号相同` |
| 401 | 未登录或 token 失效 | `未授权，请重新登录` |
| 409 | 新手机号已被未注销账号占用 | `该手机号已被使用` |
| 429 | 发送过于频繁或达到上限 | `发送过于频繁，请稍后再试` |

### 2.2 提交更换手机号

**POST** `/app/api/v1/settings/phone-change/confirm`

**说明**

- 当前登录态用于证明操作者已登录当前账号。
- 服务端根据 `customer.id` 查询当前绑定手机号，不信任前端传入旧手机号。
- 仅验证新手机号验证码。
- 更换成功后递增 token version，当前登录态立即失效。

**请求头**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| Authorization | string | 是 | `Bearer <accessToken>` |
| X-User-Id | number | 是 | 当前乘客 ID |

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| newPhone | string | 是 | 新手机号 |
| code | string | 是 | 新手机号验证码 |

**响应 data**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| changed | boolean | 是 | 是否更换成功 |
| requireLogin | boolean | 是 | 是否需要重新登录；成功时固定 `true` |
| maskedNewPhone | string | 是 | 新手机号脱敏展示 |

**成功响应示例**

```json
{
  "code": 200,
  "msg": "手机号已更换，请重新登录",
  "data": {
    "changed": true,
    "requireLogin": true,
    "maskedNewPhone": "139****9000"
  }
}
```

**错误码**

| code | 场景 | 文案建议 |
|---:|---|---|
| 400 | 参数错误 / 新手机号与当前手机号相同 | `参数不正确` |
| 401 | 未登录、token 失效、验证码错误或过期 | `未授权，请重新登录` / `验证码错误或已过期` |
| 404 | 当前账号不存在或已注销 | `账号不存在或已注销` |
| 409 | 新手机号已被未注销账号占用 | `该手机号已被使用` |

---

## 3. 注销账号

### 3.1 发送注销验证码

**POST** `/app/api/v1/settings/account-cancel/sms/send`

**说明**

- 注销验证码发送到当前账号绑定手机号。
- 前端不传手机号；服务端根据 `customer.id` 查询当前绑定手机号。
- 该验证码只用于注销账号，不复用登录验证码。

**请求头**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| Authorization | string | 是 | `Bearer <accessToken>` |
| X-User-Id | number | 是 | 当前乘客 ID |

**请求体**

无。

**响应 data**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| mockCode | string\|null | 否 | 本地 mock 短信开启时返回验证码；生产为空 |
| maskedPhone | string | 是 | 验证码发送到的手机号脱敏展示 |

**成功响应示例**

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "mockCode": "123456",
    "maskedPhone": "138****8000"
  }
}
```

### 3.2 提交注销账号

**POST** `/app/api/v1/settings/account-cancel/confirm`

**说明**

- 注销前必须校验当前账号没有进行中订单。
- 注销账号采用逻辑删除：`customer.is_deleted=1`。
- 注销成功后递增 token version，当前登录态立即失效。
- 注销后同手机号允许重新注册，新账号使用新的 `customer.id`，不继承旧订单。

**请求头**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| Authorization | string | 是 | `Bearer <accessToken>` |
| X-User-Id | number | 是 | 当前乘客 ID |

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| code | string | 是 | 注销验证码 |
| confirm | boolean | 是 | 二次确认标识，必须为 `true` |

**响应 data**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| cancelled | boolean | 是 | 是否注销成功 |
| requireLogin | boolean | 是 | 是否需要重新登录；成功时固定 `true` |

**成功响应示例**

```json
{
  "code": 200,
  "msg": "账号已注销",
  "data": {
    "cancelled": true,
    "requireLogin": true
  }
}
```

**错误码**

| code | 场景 | 文案建议 |
|---:|---|---|
| 400 | 未勾选二次确认 / 参数错误 | `请确认注销风险后再提交` |
| 401 | 未登录、token 失效、验证码错误或过期 | `未授权，请重新登录` / `验证码错误或已过期` |
| 404 | 当前账号不存在或已注销 | `账号不存在或已注销` |
| 409 | 存在进行中订单 | `当前存在进行中订单，请先完成或取消订单后再注销` |

---

## 4. 与现有接口关系

- 登录与普通短信登录仍沿用：
  - `POST /app/api/v1/auth/sms/send`
  - `POST /app/api/v1/auth/login-sms`
  - `POST /app/api/v1/auth/login-password`
- 更换手机号、注销账号不复用登录短信验证码。
- 退出登录仍沿用：
  - `POST /app/api/v1/auth/logout`
- 注销账号不是退出登录的别名，注销会逻辑删除账号。

---

## 5. 当前实现状态

- 本文档为待开发接口契约。
- 接口路径、字段和错误码以后端实际落地为准，但不得违背 PRD 中关于用户 ID、历史订单保留、逻辑删除、重新注册的核心口径。

