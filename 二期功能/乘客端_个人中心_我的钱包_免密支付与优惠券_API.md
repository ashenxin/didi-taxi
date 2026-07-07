# 乘客端个人中心「我的钱包：免密支付与优惠券」二期 API

> 本文档描述「我的钱包」免密支付与优惠券所需接口、请求参数、返回参数和约定错误码。
> 产品口径见《乘客端_个人中心_我的钱包_免密支付与优惠券_PRD.md》，技术实现见《乘客端_个人中心_我的钱包_免密支付与优惠券_TECH.md》。
> 车队营销优惠券的后台模板接口、登录领券接口和内部用券接口以《车队营销优惠券_API.md》为准；本文仅保留钱包展示、免密支付与现有乘客查询入口。

---

## 0. 通用约定

### 0.1 统一返回

所有接口统一返回：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| code | number | 是 | 200 成功；400/401/403/404/409/429/500/502 等错误码 |
| msg | string | 是 | 提示信息 |
| data | object\|array\|null | 否 | 业务数据 |

### 0.2 鉴权与身份

- 乘客端接口均通过网关访问。
- 乘客端接口需要 `Authorization: Bearer <accessToken>`。
- `passenger-api` 校验 JWT 后注入 `X-User-Id`，该值对应 `customer.id`。
- 前端不得传入或覆盖 `passengerId`。

### 0.3 枚举

支付渠道：

| 值 | 说明 |
|---|---|
| ALIPAY | 支付宝 |
| WECHAT | 微信 |

免密协议状态：

| 值 | 说明 |
|---|---|
| SIGNING | 签约中 |
| ACTIVE | 已开通 |
| CLOSED | 已关闭 |
| FAILED | 签约失败 |

优惠券状态：

| 值 | 说明 |
|---|---|
| UNUSED | 未使用 |
| LOCKED | 已锁定 |
| USED | 已使用 |
| EXPIRED | 已过期 |
| INVALID | 已失效 |

优惠券类型：

| 值 | 说明 |
|---|---|
| AMOUNT_OFF | 固定金额减免 |
| PERCENT_OFF | 比例折扣，可配封顶金额 |
| SPECIAL | 特殊活动券 |

---

## 1. 钱包首页

### 1.1 查询钱包首页摘要

**GET** `/app/api/v1/wallet/summary`

**说明**

- 供「我的钱包」页面展示免密状态、可用券数量及其他入口状态。
- 银行卡、借钱、车险本期仅返回占位状态。

**响应 data**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| autoPayEnabled | boolean | 是 | 是否存在默认且有效的免密支付 |
| defaultAutoPayChannel | string\|null | 否 | 默认免密渠道 |
| defaultAutoPayAgreement | object\|null | 否 | 默认免密协议信息；前端可优先展示该字段 |
| availableCouponCount | number | 是 | 可用优惠券数量 |
| bankCardEnabled | boolean | 是 | 本期固定 false |
| loanEnabled | boolean | 是 | 本期固定 false |
| carInsuranceEnabled | boolean | 是 | 本期固定 false |

**响应示例**

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "autoPayEnabled": true,
    "defaultAutoPayChannel": "ALIPAY",
    "availableCouponCount": 3,
    "bankCardEnabled": false,
    "loanEnabled": false,
    "carInsuranceEnabled": false
  }
}
```

---

## 2. 免密支付设置

### 2.1 查询免密支付列表

**GET** `/app/api/v1/wallet/auto-pay/agreements`

**响应 data**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| agreements | array | 是 | 免密协议列表 |

agreements item：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| agreementId | number | 是 | 协议 ID |
| channel | string | 是 | ALIPAY / WECHAT |
| channelName | string | 是 | 展示名 |
| status | string | 是 | SIGNING / ACTIVE / CLOSED / FAILED |
| defaulted | boolean | 是 | 是否默认 |
| signedAt | string\|null | 否 | 开通时间 |
| lastUsedAt | string\|null | 否 | 最近使用时间 |

### 2.2 发起免密签约

**POST** `/app/api/v1/wallet/auto-pay/agreements/sign`

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| channel | string | 是 | ALIPAY / WECHAT |
| signScene | string | 否 | APP / H5 / MINI_PROGRAM，本期可默认 H5 |

**响应 data**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| agreementId | number | 是 | 本地协议 ID |
| channel | string | 是 | 签约渠道 |
| signUrl | string\|null | 否 | 渠道签约跳转地址；mock 时可为空 |
| mockSigned | boolean | 是 | 本地 mock 是否已直接开通 |

**错误码**

| code | 场景 | 文案建议 |
|---:|---|---|
| 400 | 渠道不支持 | `暂不支持该支付渠道` |
| 401 | 未登录 | `未授权，请重新登录` |
| 409 | 已存在开通中或已开通协议 | `该渠道免密支付已存在` |

### 2.3 查询签约结果

**GET** `/app/api/v1/wallet/auto-pay/agreements/{agreementId}`

**说明**

- 前端从渠道页返回后可轮询协议状态。
- 后端也可由渠道回调更新协议状态。

### 2.4 设置默认免密渠道

**POST** `/app/api/v1/wallet/auto-pay/agreements/{agreementId}/default`

**说明**

- 仅 `ACTIVE` 状态协议可设为默认。
- 成功后同一乘客其他协议自动取消默认。

### 2.5 关闭免密支付

**POST** `/app/api/v1/wallet/auto-pay/agreements/{agreementId}/close`

**说明**

- 关闭本地协议，并调用渠道解约。
- 若关闭默认渠道，关闭成功后该用户没有默认免密渠道。

---

## 3. 优惠券

### 3.1 查询我的优惠券

**GET** `/app/api/v1/wallet/coupons`

**Query**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| status | string | 否 | UNUSED / USED / EXPIRED / INVALID；不传默认 UNUSED |
| pageNo | number | 否 | 默认 1 |
| pageSize | number | 否 | 默认 10 |

**响应 data**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| list | array | 是 | 优惠券列表 |
| total | number | 是 | 总数 |
| pageNo | number | 是 | 当前页 |
| pageSize | number | 是 | 页大小 |

list item：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| couponId | number | 是 | 用户券 ID |
| templateId | number | 是 | 模板 ID |
| name/couponName | string | 是 | 券名称 |
| couponType | string | 是 | AMOUNT_OFF / PERCENT_OFF / SPECIAL |
| thresholdAmount | number | 是 | 使用门槛 |
| discountAmount | number\|null | 否 | 固定抵扣金额 |
| discountRate | number\|null | 否 | 折扣率 |
| maxDiscountAmount | number\|null | 否 | 最大优惠金额 |
| companyId | number | 是 | 发券车队承运单元 ID |
| companyName | string\|null | 否 | 发券车队/公司名称快照 |
| cityCode | string | 是 | 适用城市 |
| productCode | string | 是 | 适用产品线 |
| status | string | 是 | 券状态 |
| validStartAt | string | 是 | 有效期开始 |
| validEndAt | string | 是 | 有效期结束 |

### 3.2 查询订单可用优惠券

**GET** `/app/api/v1/wallet/coupons/available`

**Query**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| orderNo | string | 是 | 订单号 |

**说明**

- 用于订单结算页或支付前展示可用券。
- 订单必须属于当前乘客。
- 必须基于订单最终承运 `trip_order.company_id` 判断车队匹配；最终司机未确定前不得锁定强车队绑定券。
- 返回排序应与最优券规则一致：实际优惠金额最大、过期更早、用户券 ID 更小。

---

## 4. 内部接口口径

> 以下接口为服务间调用口径，后续 TECH/API 实现时可根据服务拆分调整路径。

### 4.1 锁定优惠券

**POST** `/internal/calculate/coupons/lock`

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| passengerId | number | 是 | 乘客 ID |
| orderNo | string | 是 | 订单号 |
| finalAmount | number | 是 | 优惠前最终车费 |
| companyId | number | 是 | 最终承运车队 company_id |
| cityCode | string | 是 | 城市 |
| productCode | string | 是 | 产品线 |
| couponId | number\|null | 否 | 指定券；为空则系统选择最优券 |
| manualNoCoupon | boolean | 否 | 用户是否选择不使用券 |

### 4.2 核销优惠券

**POST** `/internal/calculate/coupons/use`

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| passengerId | number | 是 | 乘客 ID |
| orderNo | string | 是 | 订单号 |
| couponId | number | 是 | 用户券 ID |

### 4.3 释放优惠券

**POST** `/internal/calculate/coupons/release`

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| passengerId | number | 是 | 乘客 ID |
| orderNo | string | 是 | 订单号 |
| couponId | number | 是 | 用户券 ID |
| reason | string | 否 | 释放原因 |

### 4.4 创建免密支付单

**POST** `/internal/wallet/payments/auto-pay`

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| orderNo | string | 是 | 订单号 |
| passengerId | number | 是 | 乘客 ID |
| amount | number | 是 | 支付金额 |
| idempotencyKey | string | 是 | 幂等键 |

**响应 data**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| paymentNo | string | 是 | 支付单号 |
| status | string | 是 | 支付状态 |
| channel | string | 是 | 实际支付渠道 |

### 4.5 车队营销优惠券内部接口

车队营销优惠券新增的登录领券、模板管理、退款恢复、结算快照字段等接口，以《车队营销优惠券_API.md》为准。本文不重复维护，避免钱包展示接口与营销规则接口分叉。
