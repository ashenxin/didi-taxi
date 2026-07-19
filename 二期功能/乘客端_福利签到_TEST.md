# 乘客端福利签到 TEST

对应福利签到 PRD/API/TECH/SQL 及异常补偿 TECH，覆盖当前已落地的 1～28 日月度签到、积分和定时对账补偿。MySQL 是签到与积分权威，Redis Bitmap 仅作签到事实辅助索引。

## 0. 当前规则

默认配置：

```text
timezone=Asia/Shanghai
cycle=MONTHLY
displayDays=28
signDays=1..28
defaultReward=5
continuous everyDays=7 reward=35 includeDefault=false
```

即普通日奖励 5 分；连续第 7、14、21、28 天奖励 35 分，不再叠加普通 5 分。

## 1. 环境与数据

- 启动 gateway、passenger-api、calculate、Redis、MySQL；验证定时任务时同时启动 XXL-Job Admin。
- 执行 calculate 正式 schema 和测试 schema。
- 准备乘客 A（无积分）、B（已有连续签到）、C（已注销积分账户）。
- 涉及日期边界时使用测试时钟/隔离环境，不直接修改生产系统时间。
- 记录 customerId、businessDate、X-Request-Id。

关键表：

- `benefit_sign_record`
- `benefit_points_account`
- `benefit_points_flow`
- `benefit_reconciliation_issue`

Redis：`benefit:sign:bitmap:{customerId}:{yyyyMM}`。

## 2. 鉴权

### T-BENEFIT-01 未登录

不带 token 调用 overview、points、sign-in。

预期：401；不得创建积分账户、签到记录或 Bitmap。

### T-BENEFIT-02 身份防伪造

使用乘客 A token，尝试在 query/body 传 B 的 customerId 或伪造 `X-User-Id`。

预期：业务身份只取网关注入的 A；B 数据不变。

## 3. 福利概览

### T-BENEFIT-03 新用户概览

调用 `GET /app/api/v1/benefits/overview`。

预期：

- code=200，availablePoints=0。
- 返回当月 28 个 day 元素。
- 1～今天之前未签到日显示未签；未来日不可签到。
- signedToday=false、continuousDays=0。
- 今日 1～28 日时 signEnabled=true，并展示预计 reward。

### T-BENEFIT-04 已签到概览

准备 B 当月连续多日记录。

预期：日历已签日期、今日状态、continuousDays、todayRewardRuleCode 与 DB 一致；刷新不新增任何记录。

### T-BENEFIT-05 29～31 日概览

业务日期设为月末 29～31 日。

预期：signEnabled=false、disabledReason=`MONTH_SIGN_CLOSED`；仍返回 28 天历史和当前积分。

### T-BENEFIT-06 配置非法

测试 enabled=false、timezone 非法、起止日/奖励配置非法。

预期：overview 返回 signEnabled=false、`CONFIG_ERROR`；日志明确配置项；不写 MySQL/Redis。

## 4. 积分查询

### T-BENEFIT-07 无积分账户

调用 `GET /app/api/v1/benefits/points`。

预期：返回 available/earned/used/cleared=0 的稳定结构，而不是 404。

### T-BENEFIT-08 已有账户

预期：

```text
availablePoints = 所有有效 IN + OUT delta 汇总后的余额
totalEarnedPoints / totalUsedPoints / totalClearedPoints 与账户字段一致
accountStatus 正确
```

查询不得修改 version、余额或流水。

## 5. 首次签到

### T-BENEFIT-09 普通日签到

在非 7 倍数连续日调用 `POST /app/api/v1/benefits/sign-in`，携带唯一 `X-Request-Id`。

预期：

- `newSigned=true`、rewardPoints=5、ruleCode=`SIGN_IN_DAILY`。
- 新增一条 sign record，continuousDays 正确。
- 新增一条 IN 积分流水，balanceBefore/After 正确。
- account available/totalEarned 增加 5，version 递增。
- 事务提交后当月 Bitmap 对应 bit=1。

### T-BENEFIT-10 连续第 7 天

准备前 6 天连续签到，第 7 天签到。

预期：rewardPoints=35、ruleCode=`SIGN_IN_CONTINUOUS_7`；不再额外加 5，总余额只增加 35。

### T-BENEFIT-11 连续第 14/21/28 天

分别构造连续天数。

预期：每个 7 的倍数按配置奖励 35；规则 snapshot 和 continuousDays 正确。

### T-BENEFIT-12 断签后重新计算

前一天未签到，今天签到。

预期：continuousDays=1，使用普通奖励；累计签到记录保留但不当作连续。

### T-BENEFIT-13 月初重置连续天数

上月 28 日已签到，本月 1 日签到。

预期：本月 continuousDays=1；Bitmap 使用新 yyyyMM key；积分余额跨月保留。

## 6. 幂等与并发

### T-BENEFIT-14 相同 requestId 重试

同一用户、同一天、同 requestId 调用两次。

预期：第一次 newSigned=true，之后返回“今日已签到”；只一条签到、一条奖励流水，不重复加分。

### T-BENEFIT-15 不同 requestId 重复签到

同一天使用两个 requestId。

预期：日期唯一约束仍保证只奖励一次；第二次 rewardPoints=0。

### T-BENEFIT-16 并发首次创建账户

无账户用户并发签到两次。

预期：账户唯一；DuplicateKey 后重新锁定读取；只有一条签到和奖励流水；余额不为 10/70 等双倍值。

### T-BENEFIT-17 并发多请求压测

同用户同日并发 20 次。

预期：全部响应可解释；最多一次 newSigned=true；账户 version、lastPointsFlowId 和流水一致。

## 7. 不可签到状态

### T-BENEFIT-18 29～31 日签到

预期：业务成功的不可签到态或 API 约定错误；`newSigned=false`、rewardPoints=0、disabledReason=`MONTH_SIGN_CLOSED`；无 DB/Redis 写入。

### T-BENEFIT-19 已注销账户

对 accountStatus=CANCELLED 的用户调用签到。

预期：拒绝积分入账；不能把账户重新激活。

### T-BENEFIT-20 配置异常签到

预期：`CONFIG_ERROR`；三张表和 Bitmap 均不变化。

## 8. 注销联动

### T-BENEFIT-21 有余额注销

通过设置页完成账号注销。

预期：

- availablePoints 归零。
- totalClearedPoints 增加注销前余额。
- accountStatus=`CANCELLED`、version 递增。
- 新增 OUT 流水，bizType=`ACCOUNT_CANCEL_CLEAR`，delta 为负余额，before/after 正确。
- 历史签到和积分流水不物理删除。

### T-BENEFIT-22 零余额/无账户注销

预期：形成或更新 CANCELLED 账户；不写无意义的 0 分 OUT 流水；重复清理幂等。

### T-BENEFIT-23 签到与注销并发

并发执行签到和 clearPoints。

预期：通过账户行锁串行；若签到先完成，注销清零包含本次奖励；若注销先完成，签到失败。最终不能出现 CANCELLED 账户仍有正余额。

## 9. Redis/MySQL 异常

### T-BENEFIT-24 MySQL 事务失败

模拟插入流水或更新账户失败。

预期：签到记录、流水、账户全部回滚；事务未提交时不应可靠地留下 Bitmap=1。

### T-BENEFIT-25 MySQL 成功、Redis 失败

暂停 Redis 或让 afterCommit SETBIT 失败。

预期：HTTP/日志按当前实现处理，但 MySQL 已是权威且积分只发一次；再次签到根据 sign record 返回已签到，并尝试补写 Bitmap。

### T-BENEFIT-26 Bitmap 丢失重查

删除当月 Bitmap 后查询 overview、再次签到。

预期：overview 基于 MySQL 仍正确；不得因 Bitmap 丢失重复发分。执行 `benefitSignReconciliation` 后，当月缺失 bit 被补齐，已结束月份的 Bitmap 按 MySQL 精确重建。

### T-BENEFIT-26A 当月 Bitmap 多余位

构造 MySQL 无签到、当月 Bitmap 为 1 的日期。

预期：写入 `BITMAP_EXTRA_BIT` 问题，不在当月并发签到窗口内清除 bit；不修改任何 MySQL 积分字段。

### T-BENEFIT-26B 已结束月份精确重建

构造历史月 Bitmap 缺位、多位与不存在三种情况。

预期：使用带 `runId` 的临时 key 按 MySQL 重建，临时 key 先设置 TTL，原子替换后对正式 key 执行 `PERSIST` 并逐位复核。`PERSIST` 失败时任务必须返回部分失败并留下 `BITMAP_REPAIR_FAILED`，不得把会自动过期的正式 key 报为成功。

### T-BENEFIT-26C Redis 不可用

预期：本次摘要为 `PARTIAL_FAILED`，失败数增加并留下 `BITMAP_REPAIR_FAILED`；MySQL 签到、流水和账户不变，下次任务可重试。

### T-BENEFIT-26D MySQL 三表对账

分别构造签到缺流水、重复流水、关联/奖励/规则不一致，以及账户余额、累计获得、累计清零、最后流水/签到指针、注销后余额和流水链差异。

预期：每种差异按稳定 `issue_key` 写入 `benefit_reconciliation_issue`；重复扫描只增加 `occurrence_count`，数据人工修正后再扫描转为 `RESOLVED`；全过程不自动改积分。

### T-BENEFIT-26E 任务参数与范围

预期：`DAILY/MONTH/CUSTOMER/FULL_AUDIT` 按约定范围扫描；非法 `mode/yearMonth/pageSize`、`CUSTOMER` 缺少 `customerId` 时直接失败；游标分页不遗漏不重复，任务串行执行。

## 10. 配置与安全

### T-BENEFIT-27 修改奖励配置

在测试环境把普通奖励和连续奖励改为其它合法值。

预期：新签到按新配置；历史流水/签到 rewardPoints 不改变；ruleSnapshot 可解释历史。

### T-BENEFIT-28 客户端不能传积分和日期

在 body 附加 points、date、customerId。

预期：服务端忽略未知字段或按 JSON 规则拒绝，绝不能采用客户端积分/日期。

## 11. 自动化回归

```bash
mvn -pl calculate test
mvn -pl passenger-api test
```

重点执行 `BenefitServiceTest`、`BenefitReconciliationServiceTest` 和 `BenefitReconciliationJobTest`，并保留三表快照、对账问题表、Bitmap bit、注销前后账户和并发测试结果。
