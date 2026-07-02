# 乘客端个人中心「我的订单」二期 API

> 本文档描述「我的订单」页面所需接口、请求参数、返回参数和当前实现口径。
> 产品口径见《乘客端_个人中心_我的订单_PRD.md》，测试用例见《乘客端_个人中心_我的订单_TEST.md》。

---

## 0. 通用约定

### 0.1 统一返回

所有接口统一返回：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| code | number | 是 | 200 成功；400/401/403/404/409 等业务错误码 |
| msg | string | 是 | 提示信息 |
| data | object\|null | 否 | 业务数据 |

### 0.2 鉴权与身份

- 业务接口通过网关访问
- 请求头需带 `Authorization: Bearer <accessToken>`
- 网关会注入 `X-User-Id`
- 当前实现中，订单列表接口仍支持读取 `X-User-Id` 作为乘客身份

---

## 1. 我的订单列表

### 1.1 查询我的订单

**GET** `/app/api/v1/orders`

**说明**

- 供乘客个人中心「我的订单」页面使用
- 支持三种筛选
- 按时间倒序

**请求头**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| Authorization | string | 是 | `Bearer <accessToken>` |
| X-User-Id | number | 是 | 网关注入的当前乘客 id |

**请求参数**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| type | string | 否 | 订单类型筛选：`ALL` / `TO_DEPART` / `REFUND_CANCEL`；也支持中文 `全部` / `待出发` / `退款与取消` |
| pageNo | number | 否 | 页码，默认 `1` |
| pageSize | number | 否 | 每页条数，默认 `10` |

**响应 data**（`PassengerOrderPageVO`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| list | array | 是 | 订单列表 |
| total | number | 是 | 当前筛选条件下的总数 |
| pageNo | number | 是 | 当前页码 |
| pageSize | number | 是 | 当前页大小 |
| type | object | 是 | 当前筛选类型 |

**`type` 枚举对象**

| 字段 | 类型 | 说明 |
|---|---|---|
| code | string | 类型编码 |
| label | string | 中文名称 |

**`list[]` 结构**（`PassengerOrderListItemVO`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| orderNo | string | 是 | 订单号 |
| originAddress | string | 否 | 起点地址 |
| destAddress | string | 否 | 终点地址 |
| status | object | 是 | 订单状态枚举对象 |
| estimatedAmount | number | 否 | 预估金额 |
| finalAmount | number | 否 | 实付金额 |
| driver | object\|null | 否 | 司机摘要 |
| timestamps | object | 是 | 时间戳信息 |
| cancelBy | number\|null | 否 | 取消方 |
| cancelReason | string\|null | 否 | 取消原因 |
| reDispatching | boolean | 否 | 是否正在重新派单 |
| actions | array | 是 | 按钮占位 |

**`actions[]` 结构**（`PassengerOrderActionVO`）

| 字段 | 类型 | 说明 |
|---|---|---|
| code | string | 按钮编码 |
| label | string | 按钮文案 |
| disabled | boolean | 是否禁用 |
| implemented | boolean | 是否已实现真实业务 |

**按钮占位约定**

- `APPLY_INVOICE` -> `申请开票`
- `RETURN_TRIP` -> `呼叫返程`
- `RATE` -> `评价`
- 当前实现里三者均为 `disabled=true`、`implemented=false`

**请求示例**

```http
GET /app/api/v1/orders?type=待出发&pageNo=1&pageSize=10
Authorization: Bearer eyJ...
X-User-Id: 10001
```

**响应示例**

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "list": [
      {
        "orderNo": "OD202606300001",
        "originAddress": "杭州东站",
        "destAddress": "西湖景区",
        "status": { "code": 0, "en": "CREATED", "zh": "待派单" },
        "estimatedAmount": 28.5,
        "finalAmount": null,
        "driver": null,
        "timestamps": { "createdAt": "2026-06-30T10:00:00" },
        "cancelBy": null,
        "cancelReason": null,
        "reDispatching": false,
        "actions": [
          { "code": "APPLY_INVOICE", "label": "申请开票", "disabled": true, "implemented": false },
          { "code": "RETURN_TRIP", "label": "呼叫返程", "disabled": true, "implemented": false },
          { "code": "RATE", "label": "评价", "disabled": true, "implemented": false }
        ]
      }
    ],
    "total": 1,
    "pageNo": 1,
    "pageSize": 10,
    "type": { "code": "TO_DEPART", "label": "待出发" }
  }
}
```

---

## 2. 与现有订单详情接口的关系

- 列表页仅负责展示概要
- 详情页仍使用：
  - `GET /app/api/v1/orders/{orderNo}`
- 列表页点击某一项后，可进入详情页查看完整信息

---

## 3. 当前实现说明

- 本接口已在 `passenger-api` 落地
- 列表结果基于 `order-service` 的分页查询聚合
- 当前筛选口径与实现保持一致：
  - `全部`：全部本人订单
  - `待出发`：`CREATED / ASSIGNED / PENDING_DRIVER_CONFIRM / ACCEPTED / ARRIVED`
  - `退款与取消`：`CANCELLED`
