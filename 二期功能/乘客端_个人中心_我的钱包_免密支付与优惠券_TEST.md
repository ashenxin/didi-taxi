# 乘客端个人中心「我的钱包：免密支付与优惠券」二期 TEST

> 本文档用于「我的钱包」免密支付与优惠券功能的验收回归。
> 接口见《乘客端_个人中心_我的钱包_免密支付与优惠券_API.md》，产品口径见《乘客端_个人中心_我的钱包_免密支付与优惠券_PRD.md》。
> 车队营销优惠券后台配置、登录领券、最优券计算等专项测试还需参考《车队营销优惠券_PRD.md》《车队营销优惠券_API.md》。
> 实现状态（2026-07-16）：T-WALLET-01～13 可按当前对外接口执行；T-WALLET-14～20A 所需内部能力部分已存在，但完单自动编排尚未贯通，当前作为待实现验收用例，不能记为“已通过”。真实支付回调与退款不在当前实现范围。

---

## 0. 测试范围

- 个人中心展示「我的钱包」入口。
- 「我的钱包」展示五个入口：
  - 免密支付设置
  - 银行卡
  - 优惠券
  - 借钱
  - 车险
- 免密支付：
  - 查询支付宝/微信开通状态
  - 发起签约
  - 设置默认渠道
  - 关闭免密
  - 完单后自动扣款
- 优惠券：
  - 查询用户券列表
  - 查询订单可用券
  - 锁定、核销、释放
- 结算：
  - 不新增 `trip_order` 支付/优惠字段
  - 使用 `trip_order_settlement` 保存结算快照
  - 保存平台服务费与承运侧收入快照

---

## 1. 环境准备

- `gateway`、`passenger-api`、`passenger`、`order`、`calculate` 可用。
- MySQL 存在 `passenger`、`order`、`calculate`、`wallet` 库。
- Redis 可用。
- 准备乘客账号 A，可正常登录。
- 准备账号 A 的可完单订单。
- 准备至少一张账号 A 可用优惠券。
- 准备一张与最终承运 `company_id + city_code + product_code` 匹配的车队券。
- 支付宝/微信渠道本期可使用 mock 实现签约与扣款。

---

## 2. 页面验收

### T-WALLET-01 个人中心展示我的钱包入口

- **步骤**
  1. 登录乘客端。
  2. 进入个人中心。
- **预期**
  - 可看到「我的钱包」入口。
  - 入口位于「设置」与「客服中心」之间。

### T-WALLET-02 我的钱包页面入口顺序

- **步骤**
  1. 点击「我的钱包」。
- **预期**
  - 页面展示：
    1. 免密支付设置
    2. 银行卡
    3. 优惠券
    4. 借钱
    5. 车险

### T-WALLET-03 非本期入口占位

- **步骤**
  1. 点击银行卡、借钱、车险。
- **预期**
  - 不进入真实业务。
  - 提示“功能开发中”或进入占位页。

---

## 3. 免密支付接口测试

### T-WALLET-04 查询免密支付列表

- **接口**：`GET /app/api/v1/wallet/auto-pay/agreements`
- **前置**：账号 A 已登录。
- **预期**
  - 返回支付宝、微信相关协议状态。
  - 不返回其他用户协议。

### T-WALLET-05 发起支付宝免密签约成功

- **接口**：`POST /app/api/v1/wallet/auto-pay/agreements/sign`
- **请求体**：`{ "channel": "ALIPAY", "signScene": "H5" }`
- **预期**
  - 返回 `agreementId`。
  - `wallet_auto_pay_agreement` 写入或更新账号 A 的支付宝协议。
  - mock 环境可直接置为 `ACTIVE`。

### T-WALLET-06 发起微信免密签约成功

- **接口**：`POST /app/api/v1/wallet/auto-pay/agreements/sign`
- **请求体**：`{ "channel": "WECHAT", "signScene": "H5" }`
- **预期**
  - 返回 `agreementId`。
  - `wallet_auto_pay_agreement` 写入或更新账号 A 的微信协议。

### T-WALLET-07 设置默认免密渠道

- **接口**：`POST /app/api/v1/wallet/auto-pay/agreements/{agreementId}/default`
- **前置**
  - 账号 A 已有支付宝和微信两个 `ACTIVE` 协议。
- **预期**
  - 目标协议 `is_default=1`。
  - 账号 A 其他协议 `is_default=0`。
  - 其他账号协议不受影响。

### T-WALLET-08 非 ACTIVE 协议不能设默认

- **接口**：`POST /app/api/v1/wallet/auto-pay/agreements/{agreementId}/default`
- **前置**：目标协议状态为 `FAILED` 或 `CLOSED`。
- **预期**
  - 返回 409 或约定业务错误码。
  - 默认支付方式不变化。

### T-WALLET-09 关闭免密支付

- **接口**：`POST /app/api/v1/wallet/auto-pay/agreements/{agreementId}/close`
- **预期**
  - 协议状态更新为 `CLOSED`。
  - 若关闭的是默认协议，则该协议 `is_default=0`。
  - 后续完单不再使用该协议自动扣款。

---

## 4. 优惠券接口测试

### T-WALLET-10 查询可用优惠券

- **接口**：`GET /app/api/v1/wallet/coupons?status=UNUSED`
- **前置**：账号 A 有可用券。
- **预期**
  - 返回账号 A 的可用券。
  - 券状态为 `UNUSED`。
  - 返回 `couponType`、`companyId`、`cityCode`、`productCode` 等规则展示字段。
  - 不返回其他用户券。

### T-WALLET-11 查询已使用优惠券

- **接口**：`GET /app/api/v1/wallet/coupons?status=USED`
- **预期**
  - 返回已核销券。
  - 已使用券不出现在可用券列表。

### T-WALLET-12 查询订单可用券

- **接口**：`GET /app/api/v1/wallet/coupons/available?orderNo=xxx`
- **前置**
  - 订单属于账号 A。
  - 订单金额、城市、产品线满足券规则。
  - 订单最终承运 `company_id` 与发券车队匹配。
- **预期**
  - 返回可用于该订单的券。
  - 不满足门槛、车队、城市、产品线、有效期的券不返回。
  - 返回的最优券符合“实际优惠金额最大、优惠相同优先快过期、再按券 ID 小者优先”。

### T-WALLET-13 查询他人订单可用券失败

- **接口**：`GET /app/api/v1/wallet/coupons/available?orderNo=他人订单`
- **预期**
  - 返回 403 或 404。
  - 不泄漏他人订单和券信息。

---

## 5. 完单结算测试（待编排贯通）

### T-WALLET-14 完单后锁定优惠券

- **前置**
  - 账号 A 有一张满足条件的 `UNUSED` 优惠券。
  - 订单进入完单结算。
- **预期**
  - `user_coupon.status` 从 `UNUSED` 变为 `LOCKED`。
  - `user_coupon.locked_order_no` 写入订单号。
  - `coupon_use_record` 写入 `LOCK` 流水。
  - `trip_order_settlement` 写入 `coupon_id`、`coupon_discount_amount`、`payable_amount`、`platform_service_fee_amount`、`carrier_income_amount`。
  - 锁券使用最终 `trip_order.company_id` 校验，不使用预估阶段候选司机 `company_id`。

### T-WALLET-15 支付成功后核销优惠券

- **前置**
  - 订单已锁券。
  - mock 免密扣款成功。
- **预期**
  - `wallet_payment_order.status=SUCCESS`。
  - `trip_order_settlement.payment_status=2`。
  - `trip_order_settlement.settlement_status=PAID`。
  - `user_coupon.status=USED`。
  - `coupon_use_record` 写入 `USE` 流水。

### T-WALLET-16 支付失败后释放优惠券

- **前置**
  - 订单已锁券。
  - mock 免密扣款失败。
- **预期**
  - `wallet_payment_order.status=FAILED`。
  - `trip_order_settlement.payment_status=3`。
  - 优惠券立即释放为 `UNUSED`；若释放时已过期，则按退券策略置为 `EXPIRED`。
  - `coupon_use_record` 写入 `RELEASE` 流水。

### T-WALLET-17 无默认免密渠道

- **前置**
  - 账号 A 没有 `ACTIVE + is_default=1` 的免密协议。
  - 订单进入完单结算。
- **预期**
  - 不创建免密扣款请求。
  - `trip_order_settlement.payment_status=0` 或保持待支付。
  - 优惠券锁定策略符合 TECH 约定。

### T-WALLET-17A 平台服务费与承运侧收入

- **前置**
  - 订单 `final_amount=35.00`。
  - 优惠券抵扣 `coupon_discount_amount=5.00`。
- **预期**
  - `payable_amount=30.00`。
  - `platform_service_fee_rate=0.0500`。
  - `platform_service_fee_amount=1.50`。
  - `carrier_income_amount=28.50`。

---

## 6. 幂等与并发测试（随完单支付编排验收）

### T-WALLET-18 重复支付请求不重复扣款

- **步骤**
  1. 对同一订单、同一幂等键重复触发自动扣款。
- **预期**
  - 只生成一条 `wallet_payment_order`。
  - 不重复调用渠道扣款。
  - 返回同一支付单结果。

### T-WALLET-19 同一张券并发锁定

- **步骤**
  1. 并发用同一张 `UNUSED` 优惠券锁定两个订单。
- **预期**
  - 只有一个订单锁券成功。
  - 另一个订单锁券失败或选择其他可用券。
  - 不出现同一张券被两个订单同时使用。

### T-WALLET-20 支付回调重复处理

- **步骤**
  1. 对同一支付单重复发送成功回调。
- **预期**
  - 支付单保持 `SUCCESS`。
  - 订单结算表保持已支付。
  - 优惠券只核销一次。
  - 不重复写入有副作用的核销逻辑。

### T-WALLET-20A 异步派单最终车队校验

- **步骤**
  1. 构造预估阶段候选司机属于 A 车队、最终接单司机属于 B 车队的订单。
  2. 账号 A 同时持有 A 车队券和 B 车队券。
  3. 查询订单可用券并触发结算锁券。
- **预期**
  - A 车队券不参与最终可用券。
  - B 车队券可参与计算。
  - 结算快照中保存最终承运车队和实际使用券快照。

### 当前不执行：退款恢复

当前没有真实退款流程。全额退款后的券恢复、支付单退款状态、服务费/承运侧收入回滚和对账应在退款 API 与编排落地后单独补充用例；不得仅调用 calculate 的券释放接口冒充退款闭环。

---

## 7. 表结构验收

### T-WALLET-21 trip_order 不新增支付优惠字段

- **预期**
  - 不新增 `coupon_id`、`coupon_discount_amount`、`payable_amount`、`payment_status`、`payment_no`、`paid_at` 等字段到 `trip_order`。
  - 已有 `estimated_amount`、`final_amount`、`fare_rule_snapshot` 保持不迁移。
  - 不新增优惠券字段到 `fare_rule`。

### T-WALLET-22 trip_order_settlement 存在且唯一关联订单

- **预期**
  - `order.trip_order_settlement` 存在。
  - `order_no` 唯一。
  - 同一订单只有一条结算快照。
  - 表中可保存优惠券快照、平台服务费、承运侧收入。
