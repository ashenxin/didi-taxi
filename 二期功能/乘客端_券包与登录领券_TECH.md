# 乘客端「券包与登录领券」TECH

> 记录日期：2026-07-13
> 范围：首页券包、个人中心优惠券、登录领券弹框、注销旧券作废、手机号重复领取风控。
> 代码状态：已完成 passenger H5、passenger-api、calculate 主链路开发；待环境配置、SQL 与历史数据联调。

---

## 1. 服务分工

| 模块 | 职责 |
|---|---|
| `didi-passenger-h5` | 登录后领券弹框、首页券包、个人中心优惠券展示。 |
| `passenger-api` | 乘客端 BFF；鉴权后注入 `X-User-Id` 和 `X-User-Phone`；计算手机号领取身份哈希；编排注销流程。 |
| `calculate` | 优惠券模板、用户券、领取判断、用户券分页、锁券/核销/释放、注销作废。 |
| `passenger` | 乘客账号生命周期，负责账号注销本体。 |
| `order` | 注销前订单状态、未结清订单校验。 |

---

## 2. 前端实现

### 2.1 登录领券弹框

登录成功后调用：

```http
GET /app/api/v1/wallet/coupons/claimable
```

有可领取券时展示页面内弹出框。

领取：

```http
POST /app/api/v1/wallet/coupons/claim
```

请求体：

```json
{
  "templateIds": [1001]
}
```

全部领取由前端把当前可领取券模板 ID 一次性传给 `claim`，保留后端 `claim-all` 作为兼容接口。

### 2.2 首页券包

调用：

```http
GET /app/api/v1/wallet/coupons?status=UNUSED&pageNo=1&pageSize=20
```

排序由后端保证：

```text
valid_end_at ASC, id DESC
```

### 2.3 个人中心优惠券

调用同一列表接口：

```http
GET /app/api/v1/wallet/coupons
```

页面展示字段来自 `CouponVO`，包括门槛、优惠金额、城市、产品线、发券车队、状态、有效期。

---

## 3. 后端实现

### 3.1 登录态手机号透传

`AppJwtService` 签发 token 时写入：

```text
phone
```

`PassengerJwtAuthFilter` 校验 token 后包装 request，注入：

```text
X-User-Id
X-User-Phone
```

Controller 不接受前端自传 passengerId，也不信任前端传手机号。

### 3.2 手机号领取身份

`PassengerWalletService` 计算：

```text
claimIdentityType = PHONE
claimIdentityHash = HMAC-SHA256(phoneHashSecret, normalizedPhone)
```

然后调用 calculate：

```http
GET /api/v1/coupons/claimable?passengerId=...&claimIdentityType=PHONE&claimIdentityHash=...
POST /api/v1/coupons/claim?passengerId=...
POST /api/v1/coupons/claim-all?passengerId=...
```

calculate 查询历史领取：

```text
同 template_id + passenger_id 命中 => 已领取
同 template_id + claim_identity_type/hash 命中 => 已领取
```

### 3.3 用户券字段

`user_coupon` 需要包含：

```text
claim_identity_type
claim_identity_hash
invalid_reason
invalid_at
```

新领取记录必须写入 `claim_identity_type=PHONE` 和手机号 hash。

### 3.4 注销旧券作废

注销前增加：

```http
GET /internal/calculate/coupons/locked-exists?passengerId=...
```

返回 `true` 时，passenger-api 阻止注销。

注销成功后调用：

```http
POST /internal/calculate/coupons/invalidate-by-passenger
```

请求体：

```json
{
  "passengerId": 10011,
  "reason": "ACCOUNT_CANCEL"
}
```

calculate 将旧账号 `UNUSED` 券置为：

```text
status = INVALID
invalid_reason = ACCOUNT_CANCEL
invalid_at = now
```

并写入 `coupon_use_record`：

```text
action_type = INVALIDATE
before_status = UNUSED
after_status = INVALID
reason = ACCOUNT_CANCEL
```

---

## 4. 生产配置

### 4.1 P0 必配项

生产环境必须配置：

```yaml
coupon:
  claim-identity:
    phone-hash-secret: ${COUPON_CLAIM_IDENTITY_PHONE_HASH_SECRET}
```

对应配置键：

```text
coupon.claim-identity.phone-hash-secret
```

开发值只存在于 `application-local/dev.yml`。除 `local/dev/test` 外，配置为空、少于 32 字节或仍使用以下开发值时，`passenger-api` 会拒绝启动：

```text
dev-coupon-claim-secret-change-me
```

原因：

- 该 secret 参与手机号哈希。
- 如果泄露或使用默认值，手机号 hash 更容易被离线撞库。
- 如果不同环境 secret 不一致，历史 hash 无法跨环境复用；生产一旦上线后不要随意更换，否则旧领取记录会无法命中。

### 4.2 配置变更原则

- 上线前设置稳定生产 secret。
- secret 不进入代码仓库。
- secret 不打印到日志。
- 已上线并产生领取记录后，不能直接更换 secret；确需更换时必须同步迁移 `user_coupon.claim_identity_hash`。

---

## 5. 数据迁移与索引

上线前确认 SQL 已执行：

```sql
ALTER TABLE `user_coupon`
    ADD COLUMN `claim_identity_type` VARCHAR(32) NULL COMMENT '领取身份类型：PHONE / CUSTOMER' AFTER `passenger_id`,
    ADD COLUMN `claim_identity_hash` VARCHAR(128) NULL COMMENT '领取身份哈希，如手机号HMAC-SHA256' AFTER `claim_identity_type`,
    ADD COLUMN `invalid_reason` VARCHAR(64) NULL COMMENT '失效原因：ACCOUNT_CANCEL / TEMPLATE_OFFLINE / RISK_CONTROL' AFTER `used_at`,
    ADD COLUMN `invalid_at` DATETIME NULL COMMENT '失效时间' AFTER `invalid_reason`,
    ADD KEY `idx_user_coupon_claim_identity` (`template_id`, `claim_identity_type`, `claim_identity_hash`);
```

建议在历史数据清理后增加唯一约束：

```sql
ALTER TABLE `user_coupon`
    ADD UNIQUE KEY `uk_user_coupon_template_identity`
    (`template_id`, `claim_identity_type`, `claim_identity_hash`);
```

历史数据回填：

- 已有 `user_coupon` 如果 `claim_identity_hash` 为空，无法按手机号维度阻止重复领取。
- 需要通过旧 `passenger_id -> passenger.customer.phone` 计算 hash 后回填。
- 回填前必须使用与生产一致的 `coupon.claim-identity.phone-hash-secret`。

---

## 6. 异常与边界

| 场景 | 处理 |
|---|---|
| token 中无手机号 | 返回 401，要求重新登录。 |
| 可领取券为空 | 前端不弹框。 |
| 单张券已被领完 | 本张跳过，不影响其他券。 |
| 同手机号历史领过 | `claimable` 不返回，`claim` 跳过。 |
| 注销时有锁定券 | 返回 409，提示先完成或取消相关订单。 |
| 注销成功但作废旧券失败 | 返回 502 并记录错误日志；后续建议补偿任务重试。 |

---

## 7. 验证命令

后端：

```bash
mvn -pl calculate,passenger-api -DskipTests compile
```

前端：

```bash
npm run build
```

---

## 8. 后续优化

- 给 `claim` 唯一键冲突补明确异常转换，避免并发领取时返回 500。
- 增加注销后作废失败的补偿任务。
- 增加 `displayTitle`、`displaySubtitle`、`expireSoon`，减少前端重复拼文案。
- 首页券包「去使用」跳回打车页并在订单结算时自动选最优券。
