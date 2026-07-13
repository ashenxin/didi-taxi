# 后端下周功能 TODO：券包入口与每月签到福利

> 记录日期：2026-07-12
> 范围：乘客端首页左侧「券包」、个人中心「优惠券」、福利「每月签到」。
> 目标：先明确后端边界和接口口径，下周开发时避免两个入口重复造接口，也避免签到福利在奖励、补签、日历规则上失控。

---

## 1. 首页券包与个人中心优惠券

### 1.1 功能定位

首页左边的「券包」和个人中心的「优惠券」先视作同一功能的两个入口：

```text
入口不同，数据相同，接口相同，跳转到同一套用户券列表能力。
```

后端不区分入口来源，不新增一套首页券包接口。前端可以用入口参数做埋点或 UI 高亮，但不影响业务查询结果。

### 1.2 复用接口

继续使用已有乘客端优惠券列表接口：

```http
GET /app/api/v1/wallet/coupons
```

接口归属：

| 层 | 职责 |
|---|---|
| `passenger-api` | 乘客端 BFF，校验登录态，从 JWT 获取 `passengerId`，对前端提供统一入口。 |
| `calculate` | 查询 `user_coupon`，返回用户持有券、状态、有效期和规则快照。 |
| `wallet` | 不承载优惠券规则，不新增优惠券表。 |

### 1.3 查询口径

默认查询：

```text
status 不传时，默认查询 UNUSED 可用券。
pageNo 默认 1。
pageSize 默认 10。
```

支持状态：

```text
UNUSED    未使用，可展示为可用券
USED      已使用
EXPIRED   已过期
INVALID   已失效
```

暂不把 `LOCKED` 暴露为独立 tab。若用户券被订单临时锁定，列表中可按 `UNUSED` 查询返回，并增加前端展示字段：

```text
locked: true
lockedOrderNo: 当前锁定订单号
unavailableReason: ORDER_LOCKED
```

如果下周不做锁定态展示，可先过滤 `LOCKED`，避免用户误以为可以手动选择。

### 1.4 建议补充返回字段

已有接口字段继续保留，建议补齐以下展示友好字段，方便首页券包和个人中心共用：

| 字段 | 说明 |
|---|---|
| `couponId` | 用户券 ID。 |
| `templateId` | 券模板 ID。 |
| `couponName` | 券名称。 |
| `couponType` | `AMOUNT_OFF / PERCENT_OFF / SPECIAL`。 |
| `displayTitle` | 前端可直接展示的主标题，如 `满35减5`、`9折最高减20`。 |
| `displaySubtitle` | 副标题，如 `杭州快车可用`、`限杭州一队`。 |
| `thresholdAmount` | 使用门槛。 |
| `discountAmount` | 固定减免金额。 |
| `discountRate` | 折扣率。 |
| `maxDiscountAmount` | 折扣封顶。 |
| `companyId` | 发券车队。 |
| `companyName` | 发券车队名称快照。 |
| `cityCode` | 城市。 |
| `productCode` | 产品线。 |
| `status` | 用户券状态。 |
| `validStartAt` | 生效时间。 |
| `validEndAt` | 失效时间。 |
| `expireSoon` | 是否即将过期，建议后端按 3 天内计算。 |
| `unavailableReason` | 不可用原因，列表页可选。 |

### 1.5 首页券包摘要

首页如果只需要展示数量，可以优先复用钱包摘要：

```http
GET /app/api/v1/wallet/summary
```

使用字段：

```text
availableCouponCount
```

如果首页券包需要展示最近一张券，则仍调用 `/app/api/v1/wallet/coupons?status=UNUSED&pageNo=1&pageSize=1`，不新增摘要接口。

### 1.6 下周后端 TODO

- [x] 在 `passenger-api` 确认 `/app/api/v1/wallet/coupons` 已接入 `calculate` 用户券查询，而不是 mock 数据。
- [ ] 在接口返回中统一 `couponName` 字段，兼容旧文档中的 `name/couponName`。
- [ ] 增加 `displayTitle`、`displaySubtitle`、`expireSoon`，减少两个入口重复拼装规则。
- [ ] 确认首页券包入口只传分页与状态，不传入口类型；入口埋点由前端或网关日志另做。
- [ ] 补充接口测试：同一用户从首页券包和个人中心优惠券查询，返回数据一致。
- [ ] 补充边界测试：无券、全部过期、存在锁定券、存在不同车队券。
- [x] 落地手机号维度重复领取风控：同手机号领取过同模板券后，注销再注册也不能重复领取。
- [x] 落地注销旧券作废：注销成功后旧账号 `UNUSED` 券置为 `INVALID`，`LOCKED` 券阻断注销。

### 1.7 不做范围

- 不做首页券包专属优惠券。
- 不做入口差异化筛选。
- 不做多券叠加。
- 不把优惠券放入 `wallet` 库。
- 不绕过 `calculate` 直接查询优惠券表。

---

## 2. 福利：每月签到

> 2026-07-13 更新：福利-签到领取暂缓评审，先作为 TODO 保留；当前优先闭环券包、个人中心优惠券和登录领券。

### 2.1 功能定位

福利模块先做「每月签到」MVP：

```text
乘客每天可签到一次。
签到按自然月展示日历。
连续签到只在当月内计算。
奖励先以优惠券为主，预留积分/成长值扩展。
```

本功能属于乘客增长/福利能力，不属于钱包能力。优惠券只是奖励的一种发放结果。

建议服务边界：

| 层 | 职责 |
|---|---|
| `passenger-api` | 对乘客端提供签到首页、签到动作、补签动作接口。 |
| `calculate` | 如果奖励是优惠券，负责发放用户券。 |
| `passenger` 或新增福利域 | 保存签到日历、补签次数、奖励发放记录。MVP 可先放 `passenger` 库。 |

### 2.2 MVP 规则建议

基础规则：

```text
1. 每个乘客每个自然日最多签到一次。
2. 签到日按 Asia/Shanghai 自然日计算。
3. 月历以自然月为周期，每月 1 日 00:00:00 重置展示。
4. 连续签到只在当月内累计，跨月不延续。
5. 签到成功后立即返回当天奖励结果。
```

连续签到：

```text
连续签到天数 = 本月从今天往前连续已签到天数。
如果昨天未签到，今天签到后连续天数为 1。
如果今天已经签到，重复请求返回已签到结果，不重复发奖。
```

奖励建议：

| 条件 | 奖励 |
|---|---|
| 每日签到 | 小额优惠券或仅记录签到，本期可配置为无实际奖励。 |
| 连续 3 天 | 发放一张小额优惠券。 |
| 连续 7 天 | 发放一张更高额优惠券。 |
| 当月累计 15 天 | 发放一张月度福利券。 |
| 当月满签 | 发放满签奖励券。 |

奖励落地方式：

```text
签到服务只判断奖励是否触发。
优惠券实际发放调用 calculate 内部发券接口。
签到记录保存 reward_status，确保重复请求不会重复发券。
```

### 2.3 补签规则建议

补签先做可控版本：

```text
1. 默认每月允许补签 1 次。
2. 只能补签本月漏签日期。
3. 不能补签今天之后的日期。
4. 不能补签上个月或更早日期。
5. 补签成功后参与累计签到天数。
6. 补签是否参与连续签到，MVP 建议参与，但必须记录 `sign_type=MAKEUP`。
```

补签成本暂不接入支付：

```text
MVP 不做付费补签。
MVP 不做看广告补签。
MVP 不做邀请好友得补签卡。
```

后续可扩展：

```text
makeup_card_count
makeup_source
makeup_cost_type
```

### 2.4 数据模型草案

#### passenger_signin_record

按用户 + 日期唯一，记录每日签到。

关键字段：

```text
id
passenger_id
signin_date
signin_month
sign_type              NORMAL / MAKEUP
reward_status          NONE / PENDING / ISSUED / FAILED
reward_type            NONE / COUPON / POINT
reward_ref_id          用户券 ID 或其他奖励 ID
created_at
updated_at
is_deleted
```

唯一约束：

```text
uniq_passenger_date(passenger_id, signin_date, is_deleted)
```

#### passenger_signin_month_summary

按用户 + 月份保存月度统计，避免每次日历都全量计算。

关键字段：

```text
id
passenger_id
signin_month
total_days
continuous_days
makeup_used_count
makeup_limit
last_signin_date
full_month_reward_issued
created_at
updated_at
is_deleted
```

唯一约束：

```text
uniq_passenger_month(passenger_id, signin_month, is_deleted)
```

#### passenger_signin_reward_rule

奖励规则配置表。MVP 也可以先使用配置文件，等规则稳定后落表。

关键字段：

```text
id
rule_code
rule_type              DAILY / CONTINUOUS / MONTH_TOTAL / FULL_MONTH
trigger_value
reward_type            COUPON / POINT / NONE
coupon_template_id
status                 ENABLED / DISABLED
valid_start_at
valid_end_at
created_at
updated_at
is_deleted
```

### 2.5 接口草案

#### 查询签到首页

```http
GET /app/api/v1/welfare/signin/calendar?month=2026-07
```

返回：

```json
{
  "month": "2026-07",
  "today": "2026-07-12",
  "todaySigned": true,
  "totalSignedDays": 8,
  "continuousDays": 3,
  "makeupUsedCount": 0,
  "makeupLimit": 1,
  "days": [
    {
      "date": "2026-07-12",
      "signed": true,
      "signType": "NORMAL",
      "canMakeup": false,
      "rewardIssued": true
    }
  ],
  "rewards": [
    {
      "ruleCode": "CONTINUOUS_7",
      "title": "连续签到7天",
      "triggerValue": 7,
      "achieved": false,
      "issued": false
    }
  ]
}
```

说明：

- `month` 不传时默认当前月。
- 只允许查询当前月和历史月；未来月份返回 400。
- 历史月只读，不允许补签。

#### 今日签到

```http
POST /app/api/v1/welfare/signin
```

返回：

```json
{
  "signinDate": "2026-07-12",
  "alreadySigned": false,
  "totalSignedDays": 8,
  "continuousDays": 3,
  "reward": {
    "issued": true,
    "rewardType": "COUPON",
    "rewardRefId": 5001,
    "title": "连续签到3天奖励"
  }
}
```

幂等：

```text
同一乘客同一日期重复 POST，不重复插入签到记录，不重复发奖，返回 alreadySigned=true。
```

#### 补签

```http
POST /app/api/v1/welfare/signin/makeup
```

请求：

```json
{
  "date": "2026-07-08"
}
```

返回：

```json
{
  "signinDate": "2026-07-08",
  "signType": "MAKEUP",
  "makeupUsedCount": 1,
  "makeupLimit": 1,
  "totalSignedDays": 9,
  "continuousDays": 4,
  "reward": {
    "issued": false,
    "rewardType": "NONE"
  }
}
```

### 2.6 状态与幂等边界

签到写入：

```text
先插入 passenger_signin_record。
插入成功后更新 passenger_signin_month_summary。
再判断奖励规则。
最后调用 calculate 发券。
```

重复签到：

```text
依赖 passenger_id + signin_date 唯一约束。
如果唯一键冲突，查询已有记录并返回，不再次发奖。
```

奖励发放：

```text
奖励触发必须有幂等键。
建议 idempotencyKey = SIGNIN:{passengerId}:{ruleCode}:{signinMonth}
每日奖励则使用 SIGNIN:{passengerId}:DAILY:{signinDate}
```

发券失败：

```text
签到不能回滚。
记录 reward_status=FAILED。
后续补偿任务按 FAILED 重试发券。
```

### 2.7 逻辑边界

本期明确不做：

- 不做跨月连续签到。
- 不做付费补签。
- 不做广告补签。
- 不做签到排行榜。
- 不做积分商城。
- 不做复杂任务体系。
- 不做多奖励叠加弹窗；同一次签到触发多个奖励时，接口可以返回数组，但前端可合并展示。
- 不让前端传 `passengerId`。
- 不让前端指定奖励券模板。

需要产品确认：

- 每日签到是否一定发奖励，还是只在连续/累计节点发奖励。
- 补签是否影响连续签到；本文建议影响。
- 每月补签次数是固定 1 次，还是后台可配置。
- 满签奖励是否按自然月实际天数计算。
- 签到奖励券是否为平台券概念；如果仍坚持“不做平台券”，需要指定发券车队或创建福利专用活动车队口径。

### 2.8 下周后端 TODO

- [ ] 确认签到数据归属：MVP 放 `passenger` 库，还是新建福利域。
- [ ] 定义 `passenger_signin_record` 与 `passenger_signin_month_summary` SQL。
- [ ] 定义奖励规则配置来源：配置文件优先，还是落 `passenger_signin_reward_rule` 表。
- [ ] 在 `passenger-api` 增加签到日历、今日签到、补签接口。
- [ ] 增加发券内部调用的幂等键设计，避免重复发优惠券。
- [ ] 补充补偿任务：扫描 `reward_status=FAILED` 的签到记录并重试发奖。
- [ ] 补充测试：重复签到、跨月、补签次数耗尽、补签未来日期、补签历史月份、奖励发放失败重试。

---

## 3. 和现有优惠券设计的关系

当前已有优惠券设计以《车队营销优惠券_*》为准：

- 优惠券模板、用户券、用券流水在 `calculate`。
- 钱包和券包只展示用户券，不拥有优惠券规则。
- 一笔订单最多使用一张券。
- 订单最终用券必须以最终承运 `company_id` 为准。

签到福利如果发放优惠券，必须复用 `calculate` 的用户券发放能力。不要在签到表里直接创建一套“福利券”概念，否则后续订单用券、退款退券、统计都会重复。
