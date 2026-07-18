# 第一期 MVP：乘客派单司机闭环 API

> 目标：按当前实现提供“请求参数/返回参数”表格，便于联调与验收。仍未落地的扩展会显式标注，不再与已实现接口混写。
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

### 1.4 退出登录（已实现）

**POST** `/app/api/v1/auth/logout`

**请求头**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| Authorization | string | 是 | `Bearer <accessToken>`（须含 `tv` 的 JWT，与登录签发一致） |
| X-User-Id | string | 网关注入 | 客户端不要传；直连联调时与 JWT `sub` 一致即可 |

**说明**

- 退出后递增服务端 token 版本，旧 JWT 立即失效（再访问业务接口应 401）
- 若存在「司机到达前」的在途订单（状态为 CREATED / ASSIGNED / PENDING_DRIVER_CONFIRM / ACCEPTED）：按 PRD §5.6 代为乘客取消，取消原因记为「乘客退出登录」
- 若存在已到达或行程中订单（ARRIVED / STARTED）：**不**代取消；`data.hint` 返回明确说明，仍完成登出使 token 失效

**请求体**：无（可传 `{}`）

**响应 data**（`PassengerLogoutResult`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| hint | string | 否 | 可选提示：如已代取消、或到达后未取消等 |

**响应示例**

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "hint": "已为您取消进行中的订单（退出登录）。"
  }
}
```

无在途单或无可取消单时 `data` 可为 `{ "hint": null }`。

---

## 2. 乘客下单与订单（passenger-api）

### 2.1 一步下单（当前主入口，创建后异步派单）

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

### 2.2 两段式下单（仅创建订单，兼容/演进入口）

**POST** `/app/api/v1/orders/create`

> 当前 H5/MVP 推荐入口为 **`POST /app/api/v1/orders`**（见 §2.1），其内部已经采用“两段式创建 + Outbox/Kafka 异步派单”。`/orders/create` 仅作为历史兼容入口保留，不作为当前 H5 默认调用。

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

### 2.3 订单详情（展示权威，已实现）

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
| reDispatching | boolean | 否 | 是否“正在重新派单”（当前为 `CREATED` 且已发生过司机拒单、到达前取消或确认窗超时释放） |

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
    "cancelReason": null,
    "reDispatching": false
  }
}
```

> 展示口径：当 `reDispatching=true` 且状态仍为 `CREATED` 时，乘客端文案应显示“正在为您重新派单”；否则按常规 `status` 文案（如“派单中”）。

### 2.3a 乘客订单变化通知（内部接口 + WS，已实现）

乘客端实时状态采用 **WS 事件触发 + HTTP 详情对齐**：`ORDER_CHANGED` 只提示“该订单变了”，不承载业务裁决；前端收到后调用 §2.3 订单详情接口。

**内部接口**：`POST /app/internal/v1/orders/changed`

**调用方**：`driver-api` 在司机接单、拒单、到达前取消、到达、开始、完单成功后调用。

**请求 body**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| passengerId | number | 是 | 订单所属乘客 id |
| orderNo | string | 是 | 订单号 |

**WS 下行 envelope**

```json
{
  "type": "ORDER_CHANGED",
  "ts": 1780300000000,
  "data": {
    "orderNo": "OD202604280003",
    "seq": 12
  }
}
```

说明：

- `passenger-api` 由 `PassengerInternalNotifyController` 调用 `PassengerWsNotifyService.notifyOrderChanged(...)` 推送。
- 客户端按 `orderNo + seq` 去重/抗乱序，随后拉一次 `GET /app/api/v1/orders/{orderNo}`。
- WS 正常时不需要常驻订单详情短轮询；WS 不可用时可降级轮询。

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

**请求头**：除登录身份外，必须携带 **`Idempotency-Key`**（非空、长度不超过 128）；超时重试复用原 key。
**Path 参数**：`orderNo`

**请求体**（`CancelOrderRequest`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| passengerId | number | 否 | 客户端无需传；若传必须与身份一致，否则 400 |
| cancelReason | string | 否 | 一期建议前端做单选原因（无长文本输入） |

**响应 data**：`{ "replayed": false }`；同 key、同订单、同乘客及同取消原因的成功重放为 `true`。

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
  "data": { "replayed": false }
}
```

同 key 改订单、乘客或取消原因，或把该 key 用于其它动作时返回 `409`。成功重放不重复改变状态或写取消事件，但仍通知乘客端刷新。

---

## 3. 司机端（driver-api，对外，经网关）

统一前缀：`/driver/api/v1`

### 3.1 司机登出（已实现：待接释放 + 已接未到释单 + 下线 + token 作废）

**POST** `/driver/api/v1/auth/logout`

**说明（与 `driver-api` 实现对齐）**

1. **待接指派批量释放**：对该司机在订单侧 **`listAssignedToDriver`** 范围内的单子——即 **`ASSIGNED`**、**`PENDING_DRIVER_CONFIRM`**——逐单调用 **`reject`**，`reasonCode` = **`DRIVER_LOGOUT`**；语义与手动拒单一致，订单通常 **`→ CREATED`** 并重新派单，**非** **`CANCELLED`**。单条失败不阻断登出。
2. **已接单 `ACCEPTED`（到达前）自动释单**：登出复用司机到达前取消链路，将订单 **`ACCEPTED → CREATED`**，清空司机与确认窗口信息，并再次投递派单 Outbox。该语义是 **释放改派 / 释单**，乘客侧进入重新派单，**不是**乘客登出的 **`CANCELLED`** 终态。查询或单笔释单失败仅记录日志，不阻断后续下线与 token 作废。
3. **运力下线**：`online:false`（与显式下线一致，删 GEO / `monitor_status=0` 等）。
4. **登录态**：**`driver:tv:{driverId}` INCR**，旧 JWT 立即 401。

**到达后**：`ARRIVED / STARTED / FINISHED` 等到达后或行程中订单，登出 **不会** 自动取消、不会自动释单；只处理下线和登录态作废。

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

### 3.3 听单心跳（已实现：续 Presence，可选更新 GEO）

**POST** `/driver/api/v1/drivers/{driverId}/heartbeat`

**说明**

- 司机上线听单后，H5 约每 **15 秒**调用一次。
- 请求必须经司机端鉴权；`driverId` 必须与登录身份一致。
- `lat/lng` 同时提供时，capacity 更新司机池 GEO 坐标；不提供坐标时仍续司机级 Presence。
- capacity 仅允许当前仍为听单状态（`monitor_status=1`）的司机续心跳。
- 停止心跳超过 `capacity.dispatch.driver-heartbeat-timeout-seconds`（默认 **60s**）后，XXL `capacityDriverPresenceCleanup` 会移除 Presence/GEO，并将司机下线。

**请求头**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| Authorization | string | 是 | `Bearer <accessToken>` |

**Path 参数**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| driverId | number | 是 | 司机 id（必须与登录身份一致） |

**请求体**（`DriverHeartbeatBody`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| lat | number | 否 | 纬度；若提供必须与 `lng` 同时提供，范围 `[-90, 90]` |
| lng | number | 否 | 经度；若提供必须与 `lat` 同时提供，范围 `[-180, 180]` |

**响应 data**：无

**请求示例（有定位）**

```json
{
  "lat": 30.251612,
  "lng": 120.141275
}
```

**请求示例（定位失败，仅续 Presence）**

```json
{}
```

**错误语义（节选）**：`401` 未登录或 token 失效；`403` 路径司机与当前身份不一致；`400/409` 坐标不合法或司机当前未上线听单。

---

### 3.4 指派列表（已实现）

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

### 3.5 接单（已实现）

**POST** `/driver/api/v1/orders/{orderNo}/accept`

**请求头**：必须携带 **`Idempotency-Key`**（非空、长度不超过 128）；超时重试复用原 key。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| driverId | number | 是 | 必须与登录身份一致 |

**响应 data**：`{ "replayed": false }`；成功重放为 `true`。重放会在 capacity 接单资格校验前短路，不重复执行外部校验或订单写入，但仍通知乘客刷新。

**请求示例**

```json
{
  "driverId": 80001
}
```

---

### 3.6 拒单（已实现）

**POST** `/driver/api/v1/orders/{orderNo}/reject`

**请求头**：必须携带新的 **`Idempotency-Key`**（非空、长度不超过 128）；客户端因超时重试时复用原 key。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| driverId | number | 是 | 必须与登录身份一致 |
| reasonCode | string | 是 | 单选原因码（前端写死；无输入） |

**响应 data**：`{ "replayed": false }`；同 key、同请求成功重放时为 `true`。
**语义**：`order-service` 将订单从 **`ASSIGNED` / `PENDING_DRIVER_CONFIRM` → `CREATED`**，清空指派与确认窗口字段，写 **`ORDER_DRIVER_REJECTED`** 事件，并再次投递 **`ORDER_CREATED_NEED_DISPATCH`** Outbox，进入重新派单；**`reasonCode` 不对乘客展示**。
同时写入 Redis 隔离键 `tx:dispatch:block:dp:{driverId}:{passengerId}`（TTL 30 分钟）：隔离期内 capacity 派单会跳过该司机-乘客组合，司机刷新指派单也会跳过该乘客订单。
同 key、同 `orderNo + driverId + reasonCode` 只执行一次状态迁移、事件、隔离与 Outbox；同 key 改请求内容或跨动作复用返回 `409`。

**请求示例**

```json
{
  "driverId": 80001,
  "reasonCode": "TOO_FAR"
}
```

---

### 3.7 司机取消（已接单后、到达前，已实现）

**POST** `/driver/api/v1/orders/{orderNo}/cancel`

> 与乘客取消 **`POST /app/api/v1/orders/{orderNo}/cancel`** 路径语义不同：本接口为 **司机端 BFF**，到达前释放订单并改派。

**请求头**：必须携带新的 **`Idempotency-Key`**（非空、长度不超过 128）；客户端因超时重试时复用原 key。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| driverId | number | 是 | 必须与登录身份一致 |
| reasonCode | string | 是 | 单选原因码（前端写死；无输入） |

**响应 data**：`{ "replayed": false }`；同 key、同请求成功重放时为 `true`。
**语义**：`order-service` **`POST /api/v1/orders/{orderNo}/driver/cancel`** 将 **`ACCEPTED` → `CREATED`**（仅到达前；到达后应业务错误），清空服务方与确认相关字段，写 **`ORDER_DRIVER_CANCELLED_BEFORE_ARRIVE`**，并再次投递派单 Outbox；**`reasonCode` 不对乘客展示**。
同时写入 Redis 隔离键 `tx:dispatch:block:dp:{driverId}:{passengerId}`（TTL 30 分钟）：隔离期内 capacity 派单会跳过该司机-乘客组合，司机刷新指派单也会跳过该乘客订单。
同 key、同 `orderNo + driverId + reasonCode` 只执行一次状态迁移、事件、隔离与 Outbox；同 key 改请求内容或跨动作复用返回 `409`。

**请求示例**

```json
{
  "driverId": 80001,
  "reasonCode": "TEMPORARILY_UNAVAILABLE"
}
```

---

### 3.8 到达、开始与完单（已实现）

| 动作 | 接口 | 状态迁移 |
|---|---|---|
| 到达 | `POST /driver/api/v1/orders/{orderNo}/arrive` | `ACCEPTED → ARRIVED` |
| 开始 | `POST /driver/api/v1/orders/{orderNo}/start` | `ARRIVED → STARTED` |
| 完单 | `POST /driver/api/v1/orders/{orderNo}/finish` | `STARTED → FINISHED` 并登记异步结算 |

三个接口都须携带登录身份和 **`Idempotency-Key`**，请求体中的 `driverId` 必须与登录身份一致。响应 data 均为 `{ "replayed": boolean }`。同 key、同请求成功重放不重复写状态、事件、结算记录或结算任务；同 key 改关键内容或跨动作复用返回 `409`。完单兼容字段 `distanceKm/durationMin/finalAmount` 不参与结算，也不参与幂等意图判断。

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

### 4.1a 下单预检（内部接口，已实现）

**POST** `/api/v1/orders/internal/create-preflight`

由 passenger-api 在每次下单时调用，位于地图、运力、计价和订单创建之前；乘客端不得直接调用。

**请求头**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| Idempotency-Key | string | 是 | 与对外下单请求相同；长度不超过 128 |

**请求体**：只携带乘客、城市、产品和起终点等原始下单意图，不携带路线或计价派生结果。

**响应 data**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| decision | string | 是 | `ALLOW_CREATE`、`REPLAY_SUCCESS` 或 `BLOCKED` |
| orderNo | string\|null | 否 | 重放命中的原订单号，或当前阻塞订单号 |
| blockingSettlementStatus | string\|null | 否 | `BLOCKED` 时的结算状态 |
| blockingAction | string\|null | 否 | `WAIT`、`GO_TO_PAYMENT` 或 `CONTACT_OPERATIONS` |
| plannedDistanceMeters | number\|null | 否 | `REPLAY_SUCCESS` 时返回原冻结路线距离 |
| plannedDurationSeconds | number\|null | 否 | `REPLAY_SUCCESS` 时返回原冻结预计时长 |
| distanceSource | string\|null | 否 | 原路线来源 |
| routeMockVersion | string\|null | 否 | 原路线 mock 版本 |
| estimatedAmount | number\|null | 否 | 原预估金额 |
| fareRuleId | number\|null | 否 | 原计价规则 ID |
| fareRuleSnapshot | string\|null | 否 | 原计价规则快照 |
| fareCalculationVersion | string\|null | 否 | 原计价版本 |

处理约定：

- `ALLOW_CREATE`：passenger-api 才继续调用 map、capacity、calculate 和 order create。
- `REPLAY_SUCCESS`：passenger-api 直接重建第一次响应，不重复调用上述服务，也不重复发送 WS 通知。
- `BLOCKED`：passenger-api 按 action 返回“进行中、待支付或联系运营”等 409 提示。
- 相同 key 但乘客、产品、城市或起终点不同，以及 key 为 `PROCESSING/FAILED` 等状态时，order-service 返回对应 409，最终创建接口仍保留权威幂等与并发兜底。

### 4.2 司机拒单（状态机写接口，已实现）

**POST** `/api/v1/orders/{orderNo}/reject`

**请求头**：须带 **`X-User-Id`**（与 body `driverId` 一致）和 **`Idempotency-Key`**；BFF 经 Feign 转发时会透传。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| driverId | number | 是 | 指派司机 id |
| reasonCode | string | 是 | 单选原因码（不对乘客展示） |

**响应 data**：`{ "replayed": boolean }`。第一次执行为 `false`；成功重放为 `true`，且不重复写事件、隔离键和派单 Outbox。

**错误语义（节选）**：`400` 缺失或超长 key；`403` 非指派司机；`404` 订单不存在；`409` 当前状态冲突、同 key 改内容或跨动作复用。

**请求示例**

```json
{
  "driverId": 80001,
  "reasonCode": "TOO_FAR"
}
```

---

### 4.3 司机取消（已接单、到达前，已实现）

**POST** `/api/v1/orders/{orderNo}/driver/cancel`

> 与 **`POST /api/v1/orders/{orderNo}/cancel`（乘客取消）** 路径不同；本接口仅处理 **司机**在 **`ACCEPTED`** 阶段的到达前释放。

**请求头**：**`X-User-Id`** 与 body **`driverId`** 一致，并携带 **`Idempotency-Key`**。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| driverId | number | 是 | 当前服务司机 id |
| reasonCode | string | 是 | 单选原因码（不对乘客展示） |

**响应 data**：`{ "replayed": boolean }`。第一次执行为 `false`；成功重放为 `true`，且不重复写事件、隔离键和派单 Outbox。

**错误语义（节选）**：`403` 非本单司机；`404` 订单不存在；`409` 当前状态不允许司机取消（如已到达或已非 `ACCEPTED`）。

**请求示例**

```json
{
  "driverId": 80001,
  "reasonCode": "TEMPORARILY_UNAVAILABLE"
}
```

### 4.4 其余端侧状态写接口（已实现）

乘客取消 `/{orderNo}/cancel`，以及司机接单 `/{orderNo}/accept`、到达 `/{orderNo}/arrive`、开始 `/{orderNo}/start`、完单 `/{orderNo}/finish` 均要求 `Idempotency-Key`，返回 `{ "replayed": boolean }`，并与拒单/司机取消共用 `order_idempotent_record` 的请求级裁决。相同 key 只能绑定一个动作和一份关键请求内容。

司机接单另提供内部预检 `POST /{orderNo}/accept-preflight`。BFF 在 capacity 资格校验前调用：返回 `replayed=true` 时直接恢复原成功结果；返回 `false` 时才继续资格校验并用同一 key 调用正式接单接口。

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
- 司机拒单、司机到达前取消、司机确认窗超时后订单回到 **`CREATED`**（非 **`CANCELLED`**），乘客侧展示「重新派单」类等待态；**总体等待 180s** 仍以 **`created_at`** 起算、改派不重置。确认窗超时不写司机-乘客隔离键，下一轮仍可重新派给该司机；主动拒单/到达前取消才写 30 分钟隔离键。
- 司机接单、拒单、到达前取消、到达、开始、完单成功后，`driver-api` 会通过 `POST /app/internal/v1/orders/changed` 触发乘客 WS `ORDER_CHANGED`；确认窗超时释放与总体 180s 系统取消由 `order-service` 在事务提交后经网关通知 passenger-api，再触发乘客 WS；乘客端据此拉取 §2.3 订单详情，不依赖稳态短轮询。
- 订单/运力业务扫描统一由 XXL-JOB 触发；后端不保留同逻辑 Spring `@Scheduled`。
