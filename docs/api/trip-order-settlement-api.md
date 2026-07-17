# 完单结算 API

## 乘客接口

所有接口从可信请求头 `X-User-Id` 获取乘客身份。

### 查询结算

`GET /app/api/v1/orders/{orderNo}/settlement`

返回固定字段：`settlementStatus`、`originalFare`、`discountAmount`、`payableAmount`、`paidAmount`、`couponName`、`availableChannels`、`message`。只有 `PAYMENT_REQUIRED` 可主动支付；`CALCULATING`、`PAY_CONFIRMING` 只允许刷新；`PAID` 只允许查看账单。

### 主动支付

`POST /app/api/v1/orders/{orderNo}/payments`

请求头必须携带本次操作稳定且不超过 128 字符的 `Idempotency-Key`。请求体只能包含渠道：

```json
{"channel":"ALIPAY"}
```

渠道可为 `ALIPAY` 或 `WECHAT`。乘客、订单和金额均由服务端确定，额外提交 `amount` 或 `passengerId` 返回 400。成功创建 mock 尝试后返回 `paymentNo`、状态和 `invokePayload.type=MOCK_CASHIER` 的 `checkoutUrl`。

同一 key 重试返回原支付尝试；更换渠道或重新发起必须使用新 key。旧收银台过期会被原子取消，订单恢复 `PAYMENT_REQUIRED` 后可用新 key 支付。

## 状态与错误

| 状态 | 含义 | 乘客动作 |
|---|---|---|
| `CALCULATING` | 正在最终计价/锁券 | 刷新；长时间异常联系运营 |
| `PAY_CONFIRMING` | 原支付结果确认中 | 只查询原交易 |
| `PAYMENT_REQUIRED` | 未支付或支付失败 | 选择支付宝/微信主动支付 |
| `PAID` | 已结清 | 查看账单、允许新下单 |

上一单未结清时，下单返回 409，错误码 `UNSETTLED_ORDER`，并给出 `WAIT`、`GO_TO_PAYMENT` 或 `CONTACT_OPERATIONS`。平台不会因支付失败后台自动重扣。

`ORDER_CHANGED` 仅通知客户端重新查询，不携带权威金额。
