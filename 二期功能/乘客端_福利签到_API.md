# 乘客端「福利签到」API

> 记录日期：2026-07-14
> 范围：福利首页、立即签到、积分查询。
> 状态：主功能已落地。异常对账补偿只由 XXL-Job 触发，本期不新增 HTTP API。

---

## 1. 通用约定

认证：

```text
Authorization: Bearer {passengerAccessToken}
```

用户身份：

```text
后端从 JWT 解析 customerId，前端不传 customerId。
```

业务时区：

```text
Asia/Shanghai
```

通用返回沿用现有 `ResponseVo` 包装，本文只描述 `data`。

---

## 2. 福利首页概览

```http
GET /app/api/v1/benefits/overview
```

用途：

- 进入福利页时加载 28 天签到状态。
- 展示今日是否可签到。
- 展示当前积分余额。
- 展示连续签到天数。

响应 `data` 示例：

```json
{
  "businessDate": "2026-07-14",
  "yearMonth": "202607",
  "displayDays": 28,
  "signEnabled": true,
  "disabledReason": null,
  "signedToday": false,
  "continuousDays": 6,
  "availablePoints": 125,
  "todayRewardPoints": 35,
  "todayRewardRuleCode": "SIGN_IN_CONTINUOUS_7",
  "days": [
    {
      "dayOfMonth": 1,
      "date": "2026-07-01",
      "signed": true,
      "rewardPoints": 5,
      "rewardRuleCode": "SIGN_IN_DAILY"
    },
    {
      "dayOfMonth": 7,
      "date": "2026-07-07",
      "signed": true,
      "rewardPoints": 35,
      "rewardRuleCode": "SIGN_IN_CONTINUOUS_7"
    }
  ]
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `businessDate` | 后端按 `Asia/Shanghai` 计算的业务日期 |
| `yearMonth` | 当前签到年月 |
| `displayDays` | 固定 28 |
| `signEnabled` | 今天是否允许签到 |
| `disabledReason` | 不可签到原因 |
| `signedToday` | 今日是否已签到 |
| `continuousDays` | 当月连续签到天数 |
| `availablePoints` | 当前可用积分 |
| `todayRewardPoints` | 今日若签到可获得积分；已签到时可返回今日已得积分 |
| `days` | 28 天签到列表 |

`disabledReason`：

| 值 | 说明 |
| --- | --- |
| `MONTH_SIGN_CLOSED` | 29-31 号，本月签到已结束 |
| `ACCOUNT_CANCELLED` | 账号已注销 |
| `CONFIG_ERROR` | 签到配置缺失或非法 |

---

## 3. 立即签到

```http
POST /app/api/v1/benefits/sign-in
```

请求体：

```json
{}
```

说明：

- 前端不传积分。
- 前端不传日期。
- 前端不传 `customerId`。

### 3.1 新签到成功

响应 `data` 示例：

```json
{
  "newSigned": true,
  "message": "签到成功",
  "businessDate": "2026-07-14",
  "yearMonth": "202607",
  "signedToday": true,
  "continuousDays": 7,
  "rewardPoints": 35,
  "rewardRuleCode": "SIGN_IN_CONTINUOUS_7",
  "availablePoints": 160,
  "signEnabled": true,
  "disabledReason": null
}
```

### 3.2 今日已签到

今日已签到返回业务成功态，不重复加积分。

响应 `data` 示例：

```json
{
  "newSigned": false,
  "message": "今日已签到",
  "businessDate": "2026-07-14",
  "yearMonth": "202607",
  "signedToday": true,
  "continuousDays": 7,
  "rewardPoints": 0,
  "rewardRuleCode": null,
  "availablePoints": 160,
  "signEnabled": true,
  "disabledReason": null
}
```

### 3.3 29-31 号不可签到

响应建议为业务失败或业务成功但不可签到态，具体随现有错误规范实现。`data` 建议包含：

```json
{
  "newSigned": false,
  "message": "本月签到已结束，下月 1 号刷新",
  "businessDate": "2026-07-29",
  "yearMonth": "202607",
  "signedToday": false,
  "continuousDays": 28,
  "rewardPoints": 0,
  "rewardRuleCode": null,
  "availablePoints": 260,
  "signEnabled": false,
  "disabledReason": "MONTH_SIGN_CLOSED"
}
```

### 3.4 配置异常

配置文件缺失或非法：

```json
{
  "newSigned": false,
  "message": "签到配置异常，请稍后再试",
  "signEnabled": false,
  "disabledReason": "CONFIG_ERROR"
}
```

服务端要求：

- 不写 Redis。
- 不写 MySQL。
- 输出错误日志。

---

## 4. 查询乘客积分

```http
GET /app/api/v1/benefits/points
```

用途：

- 福利页顶部展示当前积分总和。
- 前端刷新按钮调用。
- 签到成功后可再次调用做对账刷新。

响应 `data` 示例：

```json
{
  "availablePoints": 128,
  "totalEarnedPoints": 260,
  "totalUsedPoints": 0,
  "totalClearedPoints": 132,
  "accountStatus": "ACTIVE",
  "refreshedAt": "2026-07-14 21:30:00"
}
```

说明：

- 首次查询积分时，如果积分账户不存在，返回 `availablePoints = 0`。
- 查询积分时可不创建账户。
- `availablePoints` 是前端展示的积分总和。

---

## 5. 业务码建议

| 业务码 | 场景 | 前端文案 |
| --- | --- | --- |
| `SIGN_ALREADY_DONE` | 今日已签到 | 今日已签到 |
| `MONTH_SIGN_CLOSED` | 29-31 号不可签到 | 本月签到已结束，下月 1 号刷新 |
| `CONFIG_ERROR` | 签到配置缺失或非法 | 签到配置异常，请稍后再试 |
| `ACCOUNT_CANCELLED` | 账号已注销 | 当前账号不可签到 |

---

## 6. 前端刷新策略

- 福利页进入：调用 `overview`。
- 点击刷新积分：调用 `points`。
- 点击立即签到：调用 `sign-in`。
- 签到成功后：使用 `sign-in.availablePoints` 更新，也可调用 `points` 再刷新一次。
