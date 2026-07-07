# 车队营销优惠券 API

> 本文档描述车队营销优惠券一期接口草案。
> 产品口径见《车队营销优惠券_PRD.md》，技术方案见《车队营销优惠券_TECH.md》，SQL 草案见《车队营销优惠券_SQL.md》。

## 0. 通用约定

统一返回：

```json
{
  "code": 200,
  "msg": "success",
  "data": {}
}
```

枚举：

```text
couponType: AMOUNT_OFF / PERCENT_OFF / SPECIAL
templateStatus: DRAFT / PUBLISHED / OFFLINE
userCouponStatus: UNUSED / LOCKED / USED / EXPIRED
issueType: LOGIN_CLAIM
sourceType: LOGIN_POPUP / BANNER / AD / ACTIVITY / CONFIG
actionType: LOCK / RELEASE / USE / REFUND_RESTORE / EXPIRE
```

权限：

- 后台接口走 `/admin/**`，由 `admin-api` 聚合。
- 乘客接口走 `/app/**`，由 `passenger-api` 聚合。
- 内部接口走服务内网，不暴露给前端。
- 后台写接口仅超管可用；非超管访问返回 403。

## 1. 后台优惠券模板

### 1.1 查询某计价规则下优惠券方案

**GET** `/admin/api/v1/pricing/fare-rules/{fareRuleId}/coupons`

说明：

- 后台从计价规则详情页进入优惠券方案。
- 服务端读取该计价规则的 `company_id + city_code + product_code`。
- 不通过 `fare_rule_id` 强绑定优惠券。

请求参数：

| 参数 | 位置 | 必填 | 说明 |
|---|---|---|---|
| fareRuleId | path | 是 | 计价规则 ID |
| status | query | 否 | DRAFT / PUBLISHED / OFFLINE |
| pageNo | query | 否 | 默认 1 |
| pageSize | query | 否 | 默认 20 |

返回：

```json
{
  "total": 1,
  "pageNo": 1,
  "pageSize": 20,
  "list": [
    {
      "templateId": 1001,
      "name": "杭州快车满35减5",
      "companyId": 10,
      "companyName": "杭州一队",
      "teamId": "HZ_TEAM_001",
      "teamName": "杭州一队",
      "couponType": "AMOUNT_OFF",
      "thresholdAmount": "35.00",
      "discountAmount": "5.00",
      "discountRate": null,
      "maxDiscountAmount": null,
      "cityCode": "330100",
      "productCode": "ECONOMY",
      "validStartAt": "2026-10-01 00:00:00",
      "validEndAt": "2026-10-07 23:59:59",
      "totalCount": 1000,
      "receivedCount": 100,
      "usedCount": 20,
      "perUserLimit": 1,
      "issueType": "LOGIN_CLAIM",
      "sourceType": "LOGIN_POPUP",
      "activityCode": null,
      "status": "PUBLISHED"
    }
  ]
}
```

### 1.2 创建优惠券模板

**POST** `/admin/api/v1/coupons/templates`

权限：超管。

请求：

```json
{
  "fareRuleId": 2001,
  "name": "杭州快车满35减5",
  "couponType": "AMOUNT_OFF",
  "thresholdAmount": "35.00",
  "discountAmount": "5.00",
  "discountRate": null,
  "maxDiscountAmount": null,
  "validStartAt": "2026-10-01 00:00:00",
  "validEndAt": "2026-10-07 23:59:59",
  "totalCount": 1000,
  "perUserLimit": 1,
  "issueType": "LOGIN_CLAIM",
  "sourceType": "LOGIN_POPUP",
  "activityCode": null,
  "ruleConfig": null
}
```

说明：

- `fareRuleId` 只用于带出 `company_id + city_code + product_code`，不写入强绑定。
- 创建后状态默认为 `DRAFT`。
- `AMOUNT_OFF` 必填 `discountAmount`。
- `PERCENT_OFF` 必填 `discountRate`，封顶折扣可填 `maxDiscountAmount`。

返回：

```json
{
  "templateId": 1001,
  "status": "DRAFT"
}
```

### 1.3 更新优惠券模板

**PUT** `/admin/api/v1/coupons/templates/{templateId}`

权限：超管。

说明：

- 建议只允许编辑 `DRAFT`。
- 已发布模板如需调整，建议先下架再创建新模板；如允许改文案类字段，不得影响已领取用户券快照。

请求字段同创建接口。

### 1.4 发布优惠券模板

**POST** `/admin/api/v1/coupons/templates/{templateId}/publish`

权限：超管。

返回：

```json
{
  "templateId": 1001,
  "status": "PUBLISHED"
}
```

### 1.5 下架优惠券模板

**POST** `/admin/api/v1/coupons/templates/{templateId}/offline`

权限：超管。

返回：

```json
{
  "templateId": 1001,
  "status": "OFFLINE"
}
```

### 1.6 查询模板统计

**GET** `/admin/api/v1/coupons/templates/{templateId}/stats`

返回：

```json
{
  "templateId": 1001,
  "totalCount": 1000,
  "receivedCount": 100,
  "usedCount": 20,
  "totalDiscountAmount": "100.00"
}
```

## 2. 乘客领券

### 2.1 查询登录后可领取券

**GET** `/app/api/v1/coupons/claimable`

说明：

- 乘客登录后调用。
- 只返回当前用户未领取、未领完、已发布、有效期内的券。
- 已领完或当前用户已领取过的券不返回。

返回：

```json
{
  "claimableCount": 2,
  "list": [
    {
      "templateId": 1001,
      "name": "杭州快车满35减5",
      "couponType": "AMOUNT_OFF",
      "thresholdAmount": "35.00",
      "discountAmount": "5.00",
      "discountRate": null,
      "maxDiscountAmount": null,
      "cityCode": "330100",
      "productCode": "ECONOMY",
      "validStartAt": "2026-10-01 00:00:00",
      "validEndAt": "2026-10-07 23:59:59"
    }
  ]
}
```

### 2.2 一键领取全部可领取券

**POST** `/app/api/v1/coupons/claim-all`

说明：

- 用户在弹窗确认后调用。
- 服务端以提交时的实时状态为准，避免超发。

返回：

```json
{
  "claimedCount": 2,
  "skippedCount": 0,
  "list": [
    {
      "userCouponId": 5001,
      "templateId": 1001,
      "couponName": "杭州快车满35减5",
      "status": "UNUSED"
    }
  ]
}
```

## 3. 乘客我的优惠券

现有钱包接口可继续承接展示：

**GET** `/app/api/v1/wallet/coupons`

新增或扩展返回字段：

```json
{
  "total": 1,
  "pageNo": 1,
  "pageSize": 20,
  "list": [
    {
      "couponId": 5001,
      "templateId": 1001,
      "couponName": "杭州快车满35减5",
      "couponType": "AMOUNT_OFF",
      "thresholdAmount": "35.00",
      "discountAmount": "5.00",
      "discountRate": null,
      "maxDiscountAmount": null,
      "companyId": 10,
      "companyName": "杭州一队",
      "cityCode": "330100",
      "productCode": "ECONOMY",
      "status": "UNUSED",
      "validStartAt": "2026-10-01 00:00:00",
      "validEndAt": "2026-10-07 23:59:59"
    }
  ]
}
```

## 4. 订单可用券

### 4.1 查询订单可用券

**GET** `/app/api/v1/wallet/coupons/available`

请求参数：

| 参数 | 位置 | 必填 | 说明 |
|---|---|---|---|
| orderNo | query | 是 | 订单号 |

说明：

- 服务端必须校验订单属于当前乘客。
- 必须基于最终 `trip_order.company_id` 判断车队匹配。
- 如果最终承运司机未确定，只能返回预计可用或空列表，不得锁券。

返回：

```json
{
  "orderNo": "T202607070001",
  "finalCompanyConfirmed": true,
  "bestCouponId": 5001,
  "list": [
    {
      "couponId": 5001,
      "couponName": "杭州快车满35减5",
      "actualDiscountAmount": "5.00",
      "payableAmount": "30.00",
      "validEndAt": "2026-10-07 23:59:59"
    }
  ]
}
```

## 5. 内部 calculate 接口

### 5.1 锁定优惠券

**POST** `/internal/calculate/coupons/lock`

请求：

```json
{
  "passengerId": 1,
  "orderNo": "T202607070001",
  "couponId": 5001,
  "companyId": 10,
  "cityCode": "330100",
  "productCode": "ECONOMY",
  "finalAmount": "35.00",
  "manualNoCoupon": false
}
```

说明：

- `couponId` 为空且 `manualNoCoupon=false` 时，系统选择最优券。
- `manualNoCoupon=true` 时不锁券，返回无优惠。

返回：

```json
{
  "couponId": 5001,
  "templateId": 1001,
  "couponDiscountAmount": "5.00",
  "payableAmount": "30.00",
  "couponRuleSnapshot": "{}"
}
```

### 5.2 核销优惠券

**POST** `/internal/calculate/coupons/use`

请求：

```json
{
  "passengerId": 1,
  "orderNo": "T202607070001",
  "couponId": 5001
}
```

### 5.3 释放优惠券

**POST** `/internal/calculate/coupons/release`

请求：

```json
{
  "passengerId": 1,
  "orderNo": "T202607070001",
  "couponId": 5001,
  "reason": "PAYMENT_FAILED"
}
```

### 5.4 全额退款恢复券

**POST** `/internal/calculate/coupons/refund-restore`

请求：

```json
{
  "passengerId": 1,
  "orderNo": "T202607070001",
  "couponId": 5001,
  "reason": "FULL_REFUND"
}
```

## 6. 内部 order 结算接口

### 6.1 写入结算快照

**POST** `/internal/order/settlements`

请求：

```json
{
  "orderNo": "T202607070001",
  "passengerId": 1,
  "finalAmount": "35.00",
  "couponId": 5001,
  "couponTemplateId": 1001,
  "couponCompanyId": 10,
  "couponDiscountAmount": "5.00",
  "payableAmount": "30.00",
  "platformServiceFeeRate": "0.0500",
  "platformServiceFeeAmount": "1.50",
  "carrierIncomeAmount": "28.50",
  "couponRuleSnapshot": "{}",
  "settlementSnapshot": "{}"
}
```

说明：

- 同一 `orderNo` 只能有一条有效结算快照。
- 支付单创建后再通过已有支付状态接口更新 `payment_no/payment_status/paid_amount/paid_at`。

## 7. 错误码建议

| code | 场景 | msg |
|---:|---|---|
| 400 | 参数非法 | 参数错误 |
| 403 | 非超管访问写接口 | 无优惠券操作权限 |
| 404 | 模板或订单不存在 | 资源不存在 |
| 409 | 模板已发布不可编辑 | 已发布模板不可编辑 |
| 409 | 用户重复领取 | 已领取过该优惠券 |
| 409 | 模板已领完 | 优惠券已领完 |
| 409 | 优惠券不可用 | 无可用优惠券 |
| 409 | 优惠券已锁定或已使用 | 优惠券状态已变化 |
