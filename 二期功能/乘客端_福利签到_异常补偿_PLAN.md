# 福利签到异常补偿实施计划

> **供智能代理执行：** 必须使用子技能 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，逐项实施本计划。各步骤使用复选框（`- [ ]`）跟踪进度。

**目标：** 在 calculate 服务增加福利签到定时对账，自动收敛 Redis 位图（Bitmap），并将 MySQL 账本异常只记录到 `benefit_reconciliation_issue`，不自动修改积分。

**架构：** MySQL 签到记录始终是位图的权威来源；XXL-Job 处理器（Handler）解析限定范围参数并调用独立对账服务。对账服务按用户分页扫描、幂等写入或恢复异常记录；XXL-Job 自身日志承担批次记录，因此不创建对账批次表。

**技术栈：** Java 21、Spring Boot 3.3.5、MyBatis-Plus、Redis、XXL-JOB 3.1.0、JUnit 5、H2。

## 全局约束

- 只新增 `benefit_reconciliation_issue` 一张表。
- Redis 位图允许自动补齐或重建；签到记录、积分流水、积分账户只检测，不自动修改。
- 不新增任何 HTTP 手动触发接口；人工执行只通过 XXL-Job 控制台。
- 换号后 `customerId` 不变，不迁移积分；注销漏通知当前无法仅凭 calculate 本地数据识别，必须在文档明确边界。
- Git 提交信息使用中文；完成代码、SQL、文档与验证后生成独立提交。

---

### 任务 1：对账参数和异常持久化

**文件：**
- 新建：`calculate/src/main/java/com/sx/calculate/model/BenefitReconciliationIssue.java`
- 新建：`calculate/src/main/java/com/sx/calculate/dao/BenefitReconciliationIssueMapper.java`
- 新建：`calculate/src/main/java/com/sx/calculate/service/BenefitReconciliationService.java`
- 测试：`calculate/src/test/java/com/sx/calculate/service/BenefitReconciliationServiceTest.java`

**接口：**
- 输入：`benefit_sign_record`、`benefit_points_flow`、`benefit_points_account` 与 Redis 位图。
- 输出：`BenefitReconciliationService.reconcile(String jobParam)`，返回任务摘要并维护 `OPEN`/`RESOLVED` 状态的异常记录。

- [x] **步骤 1：编写参数校验、位图补齐、MySQL 异常留痕与恢复的失败测试**
- [x] **步骤 2：运行定向测试，确认因实现缺失而失败**
- [x] **步骤 3：实现最小的分页扫描、Redis 收敛以及异常记录的新增或更新与恢复**
- [x] **步骤 4：运行定向测试并确认通过**

### 任务 2：接入 XXL-Job

**文件：**
- 修改：`calculate/pom.xml`
- 新建：`calculate/src/main/java/com/sx/calculate/config/XxlJobConfig.java`
- 新建：`calculate/src/main/java/com/sx/calculate/job/BenefitReconciliationJob.java`
- 修改：`calculate/src/main/resources/application.yml`
- 测试：`calculate/src/test/java/com/sx/calculate/job/BenefitReconciliationJobTest.java`

**接口：**
- 输入：XXL-Job `benefitSignReconciliation` 的 JSON 参数。
- 输出：调用 `BenefitReconciliationService.reconcile` 并向任务日志输出摘要。

- [x] **步骤 1：编写处理器委托测试并确认失败**
- [x] **步骤 2：增加 `xxl-job-core`、条件化执行器配置和处理器**
- [x] **步骤 3：运行处理器测试并确认通过**

### 任务 3：同步 SQL 与文档

**文件：**
- 修改：`calculate/src/main/resources/sql/calculate_schema.sql`
- 修改：`calculate/src/test/resources/schema-test.sql`
- 修改：`二期功能/乘客端_福利签到_SQL.md`
- 修改：`二期功能/乘客端_福利签到_异常补偿_TECH.md`
- 修改：`二期功能/乘客端_福利签到_TECH.md`
- 修改：`二期功能/乘客端_福利签到_PRD.md`
- 修改：`二期功能/README.md`

**接口：**
- 输出：与代码字段一致的单表 DDL，以及换号/销号、不设置批次表、由 XXL 日志承担批次记录的准确说明。

- [x] **步骤 1：在生产数据库结构与 H2 测试数据库结构中增加异常表**
- [x] **步骤 2：删除批次表设计并更新任务流程、测试和边界说明**
- [x] **步骤 3：执行 `mvn -pl calculate test`、`git diff --check` 与关键字一致性扫描**

### 任务 4：完成前核对与提交

- [x] **步骤 1：补充历史月份位图重建成功及 `PERSIST` 失败回归测试**
- [x] **步骤 2：同步 README、AGENTS、TODO、API/PRD/TECH/SQL/TEST 文档口径**
- [x] **步骤 3：执行 `mvn -pl calculate verify`，34 个测试通过，JaCoCo 门禁通过**
- [x] **步骤 4：执行 `git diff --check` 和旧口径关键字扫描**
