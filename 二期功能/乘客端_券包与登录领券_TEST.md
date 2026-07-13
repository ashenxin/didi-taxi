# 乘客端「券包与登录领券」TEST

> 记录日期：2026-07-13
> 范围：首页券包、个人中心可用优惠、登录领券弹框、注销旧券作废、手机号重复领取风控。

---

## 1. 环境准备

- `gateway`、`passenger-api`、`passenger`、`calculate`、`order` 服务可用。
- MySQL 已添加 `user_coupon.claim_identity_type`、`claim_identity_hash`、`invalid_reason`、`invalid_at`。
- 测试环境已配置 `coupon.claim-identity.phone-hash-secret`。
- 准备手机号 A：`13700136000`。
- 后台为钱江新城出行计价规则创建并发布至少两条登录弹窗券。
- 券模板有效期覆盖当前时间，且库存未领完。

---

## 2. 登录领券弹框

### T-COUPON-PACK-01 登录后有可领取券弹框展示

步骤：

1. 使用手机号 A 登录乘客端。
2. 登录成功后停留在打车页。

预期：

- 前端调用 `GET /app/api/v1/wallet/coupons/claimable`。
- 返回可领取券时展示页面内弹出样式领券框。
- 弹框中每张券有「领取」按钮。
- 存在「全部领取」和「暂不领取」入口。

### T-COUPON-PACK-02 暂不领取

步骤：

1. 登录后弹出领券框。
2. 点击「暂不领取」。

预期：

- 弹框关闭。
- 不调用领取接口。
- `user_coupon` 不新增记录。

### T-COUPON-PACK-03 单张领取

步骤：

1. 登录后弹出领券框。
2. 点击第一张券「领取」。

预期：

- 调用 `POST /app/api/v1/wallet/coupons/claim`，请求体只包含该券 `templateId`。
- 返回 `claimedCount=1`。
- `user_coupon` 新增一条记录。
- 新记录写入 `claim_identity_type=PHONE` 和非空 `claim_identity_hash`。
- 该券从可领取弹框中变为已领取或消失。

### T-COUPON-PACK-04 全部领取

步骤：

1. 登录后弹出多张可领取券。
2. 点击「全部领取」。

预期：

- 调用 `POST /app/api/v1/wallet/coupons/claim`。
- 请求体包含当前可领取列表全部 `templateId`。
- 返回 `claimedCount` 与实际成功领取数量一致。
- 钱包摘要和券包列表刷新。

---

## 3. 首页券包

### T-COUPON-PACK-05 首页券包展示可用券

步骤：

1. 账号 A 至少持有两张 `UNUSED` 券。
2. 进入首页底部「券包」。

预期：

- 调用 `GET /app/api/v1/wallet/coupons?status=UNUSED&pageNo=1&pageSize=20`。
- 列表展示券名称、优惠规则、门槛、适用范围、有效期。
- 券按 `validEndAt` 正序排列。

### T-COUPON-PACK-06 首页券包无券状态

步骤：

1. 使用无可用券账号登录。
2. 进入首页券包。

预期：

- 接口返回 `total=0` 或空列表。
- 页面展示无券空状态。
- 不出现假数据或静态 demo 数据。

---

## 4. 个人中心可用优惠

### T-COUPON-PACK-07 个人中心优惠券数量更新

步骤：

1. 账号 A 登录并领取 2 张券。
2. 进入个人中心 -> 我的钱包。

预期：

- `GET /app/api/v1/wallet/summary` 返回 `availableCouponCount` 增加。
- 页面「可用优惠」数量与 `UNUSED` 券数量一致。

### T-COUPON-PACK-08 个人中心优惠券展示信息

步骤：

1. 进入个人中心 -> 我的钱包 -> 可用优惠。

预期：

- 展示券名称、优惠金额/折扣、门槛、适用城市、产品线、发券车队、状态、有效期。
- 不只展示简单名称和金额。

---

## 5. 手机号重复领取风控

### T-COUPON-RISK-01 同手机号注销再注册不能重复领取

步骤：

1. 手机号 A 登录。
2. 领取模板 T。
3. 注销账号。
4. 手机号 A 重新注册并登录。
5. 调用 `GET /app/api/v1/wallet/coupons/claimable`。

预期：

- 模板 T 不返回。
- 直接调用 `POST /app/api/v1/wallet/coupons/claim` 领取模板 T 时，返回跳过，不新增 `user_coupon`。

### T-COUPON-RISK-02 已使用券也不能通过注销重新领取

步骤：

1. 手机号 A 领取模板 T。
2. 下单并使用该券，使用户券状态变为 `USED`。
3. 注销账号。
4. 手机号 A 重新注册并登录。
5. 查询可领取券。

预期：

- 模板 T 不返回。
- `USED` 历史记录仍保留。

### T-COUPON-RISK-03 不同手机号可领取同一模板

步骤：

1. 手机号 A 领取模板 T。
2. 手机号 B 登录。
3. 查询可领取券。

预期：

- 若模板有效且库存未领完，手机号 B 仍可看到模板 T。
- 手机号 B 领取后写入不同的 `claim_identity_hash`。

---

## 6. 注销旧券作废

### T-COUPON-CANCEL-01 注销成功后未使用券作废

步骤：

1. 手机号 A 登录并领取模板 T。
2. 确认用户券状态为 `UNUSED`。
3. 账号注销。

预期：

- 注销成功。
- 旧 `passengerId` 下该券状态变为 `INVALID`。
- `invalid_reason=ACCOUNT_CANCEL`。
- `invalid_at` 非空。
- `coupon_use_record` 新增 `INVALIDATE` 流水。

### T-COUPON-CANCEL-02 有锁定券时禁止注销

步骤：

1. 手机号 A 持有一张券。
2. 下单结算，使券状态为 `LOCKED`。
3. 尝试注销账号。

预期：

- 返回 409。
- 提示先完成或取消相关订单。
- 账号未注销。
- 锁定券不被作废。

### T-COUPON-CANCEL-03 已使用和已过期券注销后保持原状态

步骤：

1. 准备手机号 A 的 `USED` 券和 `EXPIRED` 券。
2. 注销账号。

预期：

- `USED` 仍为 `USED`。
- `EXPIRED` 仍为 `EXPIRED`。
- 只有 `UNUSED` 变为 `INVALID`。

---

## 7. 回归命令

后端编译：

```bash
mvn -pl calculate,passenger-api -DskipTests compile
```

前端构建：

```bash
npm run build
```
