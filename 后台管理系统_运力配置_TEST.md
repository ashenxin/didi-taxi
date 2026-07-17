# 后台管理系统：运力配置与换队 TEST

本文覆盖管理端公司/车队、司机、车辆和换队审核。司机端提交/撤销换队的端侧契约仍需结合《二期功能/司机_换队功能_API.md》验证。

## 0. 环境与数据

- 启动 gateway、admin-api、capacity、Redis、MySQL。
- 准备 SUPER、浙江省管、杭州/宁波市操作员。
- 准备杭州公司 C1（有司机）、C2（无司机），宁波公司 C3；司机 D1/D2；车辆 V1/V2。
- 准备 PENDING、APPROVED、REJECTED、CANCELLED 换队申请。

## 1. 公司/车队

### T-ADMIN-CAP-01 公司分页

验证名称、teamId、省市、状态和分页筛选。

预期：字段含 id、companyName/teamName、teamId、省市；total 正确；逻辑删除记录不出现。

### T-ADMIN-CAP-02 创建公司

SUPER 创建公司，分别传和不传 teamId。

预期：显式 teamId 唯一；未传时按服务规则生成；公司+车队作为一个承运单元；省市必填且组合合法。

### T-ADMIN-CAP-03 创建负向

测试重复 teamId、空名称、非法省市、超长字段、并发创建相同 teamId。

预期：参数错误 400、唯一冲突 409；并发最多一个成功，不返回 SQL 堆栈。

### T-ADMIN-CAP-04 修改公司

修改允许字段。

预期：只更新名称等契约允许字段；不得通过 body 改技术主键、越域省市或绕过 teamId 约束。

### T-ADMIN-CAP-05 删除公司

删除 C2 和 C1。

预期：无归属司机的 C2 逻辑删除成功；有司机的 C1 拒绝；历史订单/companyId 不被级联清空。

## 2. 司机

### T-ADMIN-CAP-06 司机分页

按手机号、姓名、companyId、省市、online 查询。

预期：筛选交集准确；字段包含 provinceCode/cityCode/companyId、审核/接单/在线状态；不返回 passwordHash。

### T-ADMIN-CAP-07 司机详情

预期：基础资料、归属公司、车辆/证件字段与契约一致；敏感证件按产品策略展示或脱敏；不存在返回 404。

### T-ADMIN-CAP-08 在线状态一致性

让司机上线/心跳/下线后查询后台列表。

预期：online/monitorStatus 与 capacity DB 权威一致；Redis GEO 只是辅助，后台不得仅凭 GEO 判定。

## 3. 车辆

### T-ADMIN-CAP-09 司机名下车辆

调用 `/drivers/{driverId}/cars`。

预期：先校验司机数据域，再分页返回该司机车辆；不能用它域 driverId 枚举车辆。

### T-ADMIN-CAP-10 独立车辆列表和详情

按车牌、司机、公司、省市查询 `/cars` 和 `/cars/{id}`。

预期：关联字段正确；逻辑删除车辆不显示；跨域详情 404。

### T-ADMIN-CAP-11 分页边界

非法 pageNo/pageSize、超末页和大页。

预期：按统一分页规则处理；不得绕过 pageSize 上限拉取全库。

## 4. 数据域

### T-ADMIN-CAP-12 省市列表裁剪

浙江省管和杭州操作员分别查询公司、司机、车辆。

预期：省管只能浙江；市操作员只能杭州；越界筛选 403；total 已裁剪。

### T-ADMIN-CAP-13 写操作越权

杭州操作员修改/删除宁波公司，审核宁波司机换队。

预期：404 资源掩蔽或 403 权限拒绝，不能修改下游数据。

## 5. 换队申请查询

### T-ADMIN-TEAM-01 pending-count

预期：只统计当前数据域 PENDING；审核/撤销后数量实时变化；不能把其它城市计入角标。

### T-ADMIN-TEAM-02 列表和详情

按状态、司机、时间分页。

预期：VO 包含 driverCityCode、原/目标公司、申请原因和状态；跨域详情 404。

## 6. 换队审核

### T-ADMIN-TEAM-03 审核通过

对 PENDING 申请调用 approve。

预期：

- 状态 APPROVED，reviewedBy 取当前管理员 ID。
- 司机 companyId 更新为目标公司，必要状态按业务规则恢复。
- 审核与归属更新在事务内一致。

### T-ADMIN-TEAM-04 审核拒绝

预期：状态 REJECTED、记录审核人/原因；司机 companyId 不变。

### T-ADMIN-TEAM-05 重复与非法状态

重复 approve、已拒绝再通过、已撤销再审核。

预期：409/明确错误；不覆盖首次审核人和时间。

### T-ADMIN-TEAM-06 目标公司失效

申请后删除/禁用目标公司再审核。

预期：审核失败；司机仍属原公司；申请状态不应错误变 APPROVED。

### T-ADMIN-TEAM-07 reviewedBy 防伪造

请求中尝试传 reviewedBy。

预期：admin-api 忽略客户端值，只使用登录管理员 ID。

## 7. 下游与并发

### T-ADMIN-CAP-14 capacity 不可用

预期：admin-api 返回明确下游错误；写操作不能假成功。

### T-ADMIN-CAP-15 并发审核

两个管理员同时审核同一申请。

预期：仅一个状态条件更新成功；最终归属和审核记录一致。

## 8. 自动化与证据

```bash
mvn -pl capacity test
```

另保留管理员 claims、申请前后 driver/company/request 表快照和 Redis 在线状态证据。
