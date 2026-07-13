# 乘客端「券包与登录领券」API

> 记录日期：2026-07-13
> 范围：乘客端领券、券包列表、注销旧券作废相关接口。
> 通用返回：`{ "code": 200, "msg": "success", "data": ... }`

---

## 1. 鉴权约定

乘客端接口均需要：

```http
Authorization: Bearer <accessToken>
```

`passenger-api` 校验 JWT 后注入：

```text
X-User-Id: customer.id
X-User-Phone: token.phone
```

前端不传 `passengerId`，也不传手机号。

---

## 2. 查询可领取券

**GET** `/app/api/v1/wallet/coupons/claimable`

说明：

- 登录成功后调用。
- 返回当前乘客当前手机号身份仍可领取的模板券。
- 同手机号历史领取过的同模板券不返回。

响应 `data[]`：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | number | 优惠券模板 ID |
| name | string | 券名称 |
| couponType | string | `AMOUNT_OFF / PERCENT_OFF / SPECIAL` |
| thresholdAmount | number | 使用门槛 |
| discountAmount | number\|null | 固定减免金额 |
| discountRate | number\|null | 折扣率 |
| maxDiscountAmount | number\|null | 折扣封顶 |
| companyId | number | 发券车队/公司 ID |
| companyNameSnapshot | string\|null | 发券车队/公司名称快照 |
| cityCode | string | 适用城市 |
| productCode | string | 适用产品线 |
| validStartAt | string | 有效期开始 |
| validEndAt | string | 有效期结束 |
| totalCount | number | 总库存 |
| receivedCount | number | 已领取数量 |
| status | string | 模板状态 |

示例：

```json
{
  "code": 200,
  "msg": "success",
  "data": [
    {
      "id": 1001,
      "name": "钱江新城快车满35减5",
      "couponType": "AMOUNT_OFF",
      "thresholdAmount": 35.00,
      "discountAmount": 5.00,
      "discountRate": null,
      "maxDiscountAmount": null,
      "companyId": 10,
      "companyNameSnapshot": "钱江新城出行",
      "cityCode": "330100",
      "productCode": "ECONOMY",
      "validStartAt": "2026-07-13T00:00:00",
      "validEndAt": "2026-07-31T23:59:59",
      "totalCount": 1000,
      "receivedCount": 20,
      "status": "PUBLISHED"
    }
  ]
}
```

---

## 3. 选择领取优惠券

**POST** `/app/api/v1/wallet/coupons/claim`

请求体：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| templateIds | array<number> | 是 | 要领取的模板 ID 列表 |

示例：

```json
{
  "templateIds": [1001, 1002]
}
```

响应 `data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| claimedCount | number | 成功领取数量 |
| skippedCount | number | 因已领取、库存不足、失效等原因跳过数量 |

示例：

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "claimedCount": 1,
    "skippedCount": 1
  }
}
```

说明：

- 前端单张领取时传一个 `templateId`。
- 前端全部领取时传当前可领取列表中所有 `templateId`。
- BFF 会自动追加 `claimIdentityType=PHONE` 和 `claimIdentityHash` 调用 calculate，前端不可传。

---

## 4. 全部领取兼容接口

**POST** `/app/api/v1/wallet/coupons/claim-all`

说明：

- 保留兼容。
- 当前前端推荐使用 `/claim`，由前端传当前列表全部模板 ID。
- 后端同样会带手机号身份风控。

响应同 `/claim`。

---

## 5. 查询我的优惠券

**GET** `/app/api/v1/wallet/coupons`

Query：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| status | string | 否 | `UNUSED / LOCKED / USED / EXPIRED / INVALID`；首页券包传 `UNUSED` |
| pageNo | number | 否 | 默认 1 |
| pageSize | number | 否 | 默认 20，最大 50 |

响应 `data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| pageNo | number | 当前页 |
| pageSize | number | 页大小 |
| total | number | 总数 |
| list | array | 用户券列表 |

list item：

| 字段 | 类型 | 说明 |
|---|---|---|
| couponId | number | 用户券 ID |
| templateId | number | 模板 ID |
| couponName | string | 券名称 |
| couponType | string | 券类型 |
| thresholdAmount | number | 使用门槛 |
| discountAmount | number\|null | 固定减免金额 |
| discountRate | number\|null | 折扣率 |
| maxDiscountAmount | number\|null | 折扣封顶 |
| companyId | number | 发券车队/公司 ID |
| companyNameSnapshot | string\|null | 发券车队/公司名称快照 |
| cityCode | string | 城市 |
| productCode | string | 产品线 |
| status | string | 用户券状态 |
| validStartAt | string | 生效时间 |
| validEndAt | string | 失效时间 |

---

## 6. 钱包摘要

**GET** `/app/api/v1/wallet/summary`

本功能使用字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| availableCouponCount | number | 当前可用优惠券数量 |

说明：

- 领取成功后前端刷新该接口。
- 个人中心「可用优惠」数量以该字段为准。

---

## 7. 内部接口

### 7.1 查询是否存在锁定券

**GET** `/internal/calculate/coupons/locked-exists?passengerId=10011`

响应：

```json
{
  "code": 200,
  "msg": "success",
  "data": false
}
```

用途：

- passenger-api 注销前调用。
- `true` 表示存在订单锁定中的券，应阻止注销。

### 7.2 注销后作废旧账号未使用券

**POST** `/internal/calculate/coupons/invalidate-by-passenger`

请求体：

```json
{
  "passengerId": 10011,
  "reason": "ACCOUNT_CANCEL"
}
```

响应：

```json
{
  "code": 200,
  "msg": "success",
  "data": 2
}
```

`data` 表示本次作废的 `UNUSED` 券数量。

---

## 8. 错误码

| code | 场景 | 文案 |
|---:|---|---|
| 400 | 请求参数缺失或非法 | `passengerId不能为空` / 参数错误 |
| 401 | 未登录或 token 中无手机号 | `未授权，请重新登录` / `登录信息已失效，请重新登录` |
| 403 | 查询他人订单可用券 | `禁止查询他人订单优惠券` |
| 409 | 注销时存在锁定券 | `当前存在订单锁定中的优惠券，请先完成或取消相关订单后再注销` |
| 502 | calculate/order/passenger 服务不可用 | `优惠券服务暂时不可用，请稍后重试` |
