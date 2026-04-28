# 第一期 MVP：乘客派单司机闭环 API

> 目标：按接口提供“请求参数/返回参数”表格，**已实现**与**未实现（本期计划）**都写全，便于联调与验收。
>
> 相关文档：
> - PRD：`第一期MVP_乘客派单司机闭环_PRD.md`
> - TECH：`第一期MVP_乘客派单司机闭环_TECH.md`

---

## 0. 通用约定

### 0.1 统一返回

所有接口统一返回：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| code | number | 是 | 200 成功；400/401/403/404/409 等业务错误码 |
| msg | string | 是 | 提示信息 |
| data | object\|null | 否 | 业务数据（可能为 null） |

### 0.2 鉴权与身份

- 业务接口经网关访问时，请求头需带：`Authorization: Bearer <accessToken>`
- 网关会注入 `X-User-Id`（客户端不要伪造）；BFF 侧读取该头作为当前用户 id

---

## 1. 乘客端（passenger-api，对外，经网关）

统一前缀：`/app/api/v1`

### 1.1 发送短信验证码（已实现）

**POST** `/app/api/v1/auth/sms/send`

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| phone | string | 是 | 手机号 |

**响应 data**：无

**请求示例**

```json
{
  "phone": "13800138000"
}
```

**响应示例**

```json
{
  "code": 200,
  "msg": "success",
  "data": null
}
```

---

### 1.2 短信验证码登录（已实现）

**POST** `/app/api/v1/auth/login-sms`

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| phone | string | 是 | 手机号 |
| code | string | 是 | 验证码 |

**响应 data**（`CustomerLoginResponse`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| accessToken | string | 是 | JWT |
| tokenType | string | 是 | 固定 `Bearer` |
| expiresIn | number | 是 | 过期秒数 |
| customer | object | 是 | 乘客信息（至少含 id/phone 等） |

**请求示例**

```json
{
  "phone": "13800138000",
  "code": "123456"
}
```

**响应示例（字段以实际为准）**

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "tokenType": "Bearer",
    "expiresIn": 604800,
    "customer": {
      "id": 10001,
      "phone": "13800138000"
    }
  }
}
```

---

### 1.3 密码登录（已实现）

**POST** `/app/api/v1/auth/login-password`

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| phone | string | 是 | 手机号 |
| password | string | 是 | 密码 |

**响应 data**：同 `CustomerLoginResponse`

**请求示例**

```json
{
  "phone": "13800138000",
  "password": "123456"
}
```

---

### 1.4 退出登录（未实现，本期计划）

**POST** `/app/api/v1/auth/logout`

**说明**

- 退出后 token 立即失效（访问业务接口应返回 401）
- 若存在“司机到达前”的进行中订单：按 PRD §5.6 触发“本单取消”
- 若司机已到达：退出登录不触发取消，应提示用户“司机已到达，无法通过退出登录取消”

**请求体**：无  
**响应 data**：无

**响应示例（期望）**

```json
{
  "code": 200,
  "msg": "success",
  "data": null
}
```

---

## 2. 乘客下单与订单（passenger-api）

### 2.1 一步下单（同步 createAndAssign，已实现）

**POST** `/app/api/v1/orders`

**请求头**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| Authorization | string | 是 | `Bearer <accessToken>` |
| X-User-Id | string | 网关注入 | 客户端不要传（直连联调时手动加） |

**请求体**（`CreateAndAssignOrderBody`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| passengerId | number | 否 | 客户端无需传；若传必须与 `X-User-Id` 一致，否则 400 |
| provinceCode | string | 是 | 省份编码 |
| cityCode | string | 是 | 城市编码 |
| productCode | string | 是 | 产品/车型编码 |
| origin | object | 是 | 上车点（Place） |
| origin.name | string | 是 | 上车点名称 |
| origin.address | string | 否 | 上车点地址 |
| origin.lat | number | 否 | 纬度（无坐标时后端可 geocode） |
| origin.lng | number | 否 | 经度 |
| dest | object | 是 | 下车点（Place） |
| dest.name | string | 是 | 下车点名称 |
| dest.address | string | 否 | 下车点地址 |
| dest.lat | number | 否 | 纬度 |
| dest.lng | number | 否 | 经度 |

**响应 data**（`CreateAndAssignOrderResult`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| orderNo | string | 是 | 订单号 |
| status | object | 是 | 状态枚举对象（含 code/en/zh） |
| assignedDriver | object\|null | 否 | 指派司机摘要（无司机则为 null） |
| route | object | 是 | 路线规划结果（map-service） |
| estimate | object | 是 | 费用预估结果（calculate-service） |

**请求示例（仅地址；坐标可选）**

```json
{
  "provinceCode": "330000",
  "cityCode": "330100",
  "productCode": "ECONOMY",
  "origin": {
    "name": "杭州火车东站",
    "address": "浙江省杭州市上城区全福桥路2号杭州东站"
  },
  "dest": {
    "name": "龙翔桥地铁站",
    "address": "浙江省杭州市上城区湖滨街道龙翔桥地铁站"
  }
}
```

**响应示例（无司机时，进入等待态）**

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "orderNo": "OD202604280001",
    "status": { "code": 0, "en": "CREATED", "zh": "待派单" },
    "assignedDriver": null,
    "route": { "distanceMeters": 12345, "durationSeconds": 1560, "provider": "amap" },
    "estimate": { "ruleId": 1, "estimatedAmount": 28.5, "distanceMeters": 12345, "durationSeconds": 1560 }
  }
}
```

**响应示例（有司机时，已派单）**

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "orderNo": "OD202604280002",
    "status": { "code": 1, "en": "ASSIGNED", "zh": "已派单" },
    "assignedDriver": { "driverId": 80001, "carId": 1, "companyId": 1, "carNo": "浙A10001", "etaSeconds": 260 },
    "route": { "distanceMeters": 12345, "durationSeconds": 1560, "provider": "amap" },
    "estimate": { "ruleId": 1, "estimatedAmount": 28.5, "distanceMeters": 12345, "durationSeconds": 1560 }
  }
}
```

---

### 2.2 两段式下单（仅创建订单，已实现）

**POST** `/app/api/v1/orders/create`

**请求头**：同上  
**请求体**：同 `CreateAndAssignOrderBody`  
**响应 data**（`CreateOrderResultV1`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| orderNo | string | 是 | 订单号 |
| status | object | 是 | 通常为 CREATED（等待态） |

**请求示例**：同 `2.1`

**响应示例**

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "orderNo": "OD202604280003",
    "status": { "code": 0, "en": "CREATED", "zh": "待派单" }
  }
}
```

---

### 2.3 订单详情（轮询，已实现）

**GET** `/app/api/v1/orders/{orderNo}`

**请求头**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| Authorization | string | 是 | `Bearer <accessToken>` |

**Path 参数**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| orderNo | string | 是 | 订单号 |

**响应 data**（`PassengerOrderDetailVO`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| orderNo | string | 是 | 订单号 |
| productCode | string | 是 | 产品编码 |
| provinceCode | string | 是 | 省份编码 |
| cityCode | string | 是 | 城市编码 |
| originAddress | string | 否 | 上车点地址 |
| destAddress | string | 否 | 下车点地址 |
| status | object | 是 | 状态枚举对象（含 code/en/zh） |
| estimatedAmount | number | 否 | 预估金额 |
| finalAmount | number | 否 | 实付金额（若有） |
| driver | object\|null | 否 | 司机摘要（无司机为 null） |
| timestamps | object | 是 | 关键时间戳集合 |
| cancelBy | number\|null | 否 | 取消方（与订单库一致；未取消为 null） |
| cancelReason | string\|null | 否 | 取消原因文案 |

**响应示例（等待态）**

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "orderNo": "OD202604280003",
    "productCode": "ECONOMY",
    "provinceCode": "330000",
    "cityCode": "330100",
    "originAddress": "浙江省杭州市上城区全福桥路2号杭州东站",
    "destAddress": "浙江省杭州市上城区湖滨街道龙翔桥地铁站",
    "status": { "code": 0, "en": "CREATED", "zh": "待派单" },
    "estimatedAmount": 28.5,
    "finalAmount": null,
    "driver": null,
    "timestamps": { "createdAt": "2026-04-28T16:30:00" },
    "cancelBy": null,
    "cancelReason": null
  }
}
```

**响应示例（系统取消：无人接单/超时兜底）**

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "orderNo": "OD202604280003",
    "productCode": "ECONOMY",
    "provinceCode": "330000",
    "cityCode": "330100",
    "originAddress": "浙江省杭州市上城区全福桥路2号杭州东站",
    "destAddress": "浙江省杭州市上城区湖滨街道龙翔桥地铁站",
    "status": { "code": 6, "en": "CANCELLED", "zh": "已取消" },
    "estimatedAmount": 28.5,
    "finalAmount": null,
    "driver": null,
    "timestamps": { "createdAt": "2026-04-28T16:30:00", "cancelledAt": "2026-04-28T16:33:10" },
    "cancelBy": 3,
    "cancelReason": "附近暂无可用车辆，请稍后重试"
  }
}
```

---

### 2.4 乘客取消订单（已实现）

**POST** `/app/api/v1/orders/{orderNo}/cancel`

**请求头**：同上  
**Path 参数**：`orderNo`

**请求体**（`CancelOrderRequest`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| passengerId | number | 否 | 客户端无需传；若传必须与身份一致，否则 400 |
| cancelReason | string | 否 | 一期建议前端做单选原因（无长文本输入） |

**响应 data**：无

**请求示例**

```json
{
  "cancelReason": "不需要了"
}
```

**响应示例**

```json
{
  "code": 200,
  "msg": "success",
  "data": null
}
```

---

## 3. 司机端（driver-api，对外，经网关）

统一前缀：`/driver/api/v1`

### 3.1 司机登出（已实现；本期需补齐“到达前退出=取消本单”联动）

**POST** `/driver/api/v1/auth/logout`

**说明**

- 当前已实现：token 立即失效（递增 token version）
- 本期需补齐：到达前退出触发“本单取消/释放改派”；到达后退出不触发取消

---

### 3.2 上线/下线听单（已实现）

**POST** `/driver/api/v1/drivers/{driverId}/online`

**请求头**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| Authorization | string | 是 | `Bearer <accessToken>` |

**Path 参数**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| driverId | number | 是 | 司机 id（必须与登录身份一致） |

**请求体**（`DriverOnlineBody`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| online | boolean | 是 | true 上线 / false 下线 |
| lat | number | 否 | 纬度（若支持） |
| lng | number | 否 | 经度（若支持） |

**响应 data**：无

**请求示例**

```json
{
  "online": true,
  "lat": 30.251612,
  "lng": 120.141275
}
```

---

### 3.3 指派列表（已实现）

**GET** `/driver/api/v1/orders/assigned?driverId=`

**请求头**：`Authorization`  
**Query 参数**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| driverId | number | 否 | 可省略；若传必须与身份一致 |

**响应 data**（数组 `AssignedOrderItemVO[]`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| orderNo | string | 是 | 订单号 |
| status | string | 是 | 状态枚举名（如 ASSIGNED） |
| pickup.name | string | 否 | 上车点名称 |
| etaSeconds | number\|null | 否 | ETA 秒（可为 null） |
| offerExpiresAt | string\|null | 否 | 确认窗口截止时间（若有） |

---

### 3.4 接单（已实现）

**POST** `/driver/api/v1/orders/{orderNo}/accept`

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| driverId | number | 是 | 必须与登录身份一致 |

**响应 data**：无

**请求示例**

```json
{
  "driverId": 80001
}
```

---

### 3.5 拒单（未实现，本期计划）

**POST** `/driver/api/v1/orders/{orderNo}/reject`

**请求体（建议）**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| driverId | number | 是 | 必须与登录身份一致 |
| reasonCode | string | 是 | 单选原因码（前端写死；无输入） |

**响应 data**：无  
**本期语义**：拒单后订单进入“重新派单/改派中”；原因不对乘客展示。

**请求示例（期望）**

```json
{
  "driverId": 80001,
  "reasonCode": "TOO_FAR"
}
```

---

### 3.6 司机取消（已接单后、到达前，未实现，本期计划）

**POST** `/driver/api/v1/orders/{orderNo}/cancel`

**请求体（建议）**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| driverId | number | 是 | 必须与登录身份一致 |
| reasonCode | string | 是 | 单选原因码（前端写死；无输入） |

**响应 data**：无  
**本期语义**：到达前允许；到达后禁止；取消后订单进入“重新派单/改派中”。

**请求示例（期望）**

```json
{
  "driverId": 80001,
  "reasonCode": "TEMPORARILY_UNAVAILABLE"
}
```

---

## 4. 核心服务（order-service，供 BFF/调度调用）

统一前缀：`/api/v1/orders`

### 4.1 订单事件时间线（已实现）

**GET** `/api/v1/orders/{orderNo}/events`

**响应 data**（数组 `OrderEvent[]`，字段节选）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| eventType | string | 是 | 事件类型 |
| fromStatus | number\|null | 否 | 来源状态 |
| toStatus | number\|null | 否 | 目标状态 |
| reasonCode | string\|null | 否 | 原因码 |
| reasonDesc | string\|null | 否 | 原因描述 |
| occurredAt | string | 是 | 发生时间 |

**响应示例（字段节选）**

```json
{
  "code": 200,
  "msg": "success",
  "data": [
    {
      "orderNo": "OD202604280002",
      "eventType": "ORDER_ASSIGNED",
      "fromStatus": 0,
      "toStatus": 1,
      "reasonCode": null,
      "reasonDesc": null,
      "occurredAt": "2026-04-28T16:30:05"
    },
    {
      "orderNo": "OD202604280002",
      "eventType": "ORDER_ACCEPTED",
      "fromStatus": 1,
      "toStatus": 2,
      "reasonCode": null,
      "reasonDesc": null,
      "occurredAt": "2026-04-28T16:30:15"
    }
  ]
}
```

### 4.2 司机拒单（状态机写接口，未实现，本期计划）

**POST** `/api/v1/orders/{orderNo}/reject`

**请求体（建议）**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| driverId | number | 是 | 指派司机 id |
| reasonCode | string | 是 | 单选原因码（不对乘客展示） |

**响应 data**：无

**请求示例（期望）**

```json
{
  "driverId": 80001,
  "reasonCode": "TOO_FAR"
}
```

---

## 5. 运力/派单（capacity-service）

统一前缀：`/api/v1/dispatch`

### 5.1 最近司机（已实现）

**GET** `/api/v1/dispatch/nearest-driver`

**Query 参数**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| cityCode | string | 是 | 城市编码 |
| productCode | string | 否 | 产品/车型编码 |
| originLat | number | 否 | 上车点纬度（提供则走 GEO） |
| originLng | number | 否 | 上车点经度 |

**响应 data**（`NearestDriverResult`，字段节选）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| driverId | number | 是 | 司机 id |
| carId | number | 否 | 车辆 id |
| companyId | number | 否 | 公司 id |
| carNo | string | 否 | 车牌 |
| etaSeconds | number | 否 | ETA（可选） |

---

## 6. 备注

- 本文档已包含接口的请求/响应字段表格与 JSON 示例，可直接用于联调与验收。

