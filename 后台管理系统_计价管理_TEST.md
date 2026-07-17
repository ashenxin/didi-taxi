# 后台管理系统：计价管理与车队优惠券 TEST

本文覆盖 fare rule 管理、基础估价、车队营销优惠券模板、权限数据域和优惠计算。乘客领券风控见《二期功能/乘客端_券包与登录领券_TEST.md》；已实现的完单锁券/支付编排以《docs/testing/完单结算本地模拟联调手册.md》为准。

## 0. 环境与数据

- 启动 gateway、admin-api、calculate、capacity、order、MySQL。
- 准备 SUPER、浙江省管、杭州/宁波操作员。
- 准备杭州公司 C1、宁波公司 C2；产品线 EXPRESS；多版本 fare rule。
- 准备固定减免、比例折扣、封顶折扣模板，以及不同状态和有效期用户券。

## 1. 计价规则列表与详情

### T-ADMIN-PRICE-01 默认分页

预期：按当前规则排序，字段含公司、省市、产品线、基础价格、有效期/状态；total 正确。

### T-ADMIN-PRICE-02 组合筛选

按 companyId、cityCode、productCode、状态组合查询。

预期：交集过滤；非法枚举和公司/城市不匹配返回 400/空结果，不扩大数据域。

### T-ADMIN-PRICE-03 详情

预期：存在且域内返回；不存在/跨域 404；金额精度和时间字段无损。

## 2. 规则 CRUD

### T-ADMIN-PRICE-04 新建

SUPER 创建合法规则。

预期：金额非负、起步里程/单价等约束生效；companyId/city/product 组合有效；创建审计字段正确。

### T-ADMIN-PRICE-05 非法金额与时间

测试负价、过多小数、结束早于开始、空产品线。

预期：400；不落半成品规则。

### T-ADMIN-PRICE-06 重叠规则

创建相同范围和重叠有效期规则。

预期：按当前服务约束拒绝或形成明确版本；不得让 estimate 随机命中不同规则。

### T-ADMIN-PRICE-07 更新

更新允许字段并验证历史规则。

预期：不改变技术主键和已产生订单的 fareRuleSnapshot；跨域修改失败。

### T-ADMIN-PRICE-08 删除

删除未引用/已引用规则。

预期：按实现逻辑删除或拒绝；历史订单快照仍可解释；再次查询不返回已删除活动规则。

## 3. 费用预估

### T-ADMIN-PRICE-09 正常估价

调用 `POST /api/v1/calculate/estimate`，覆盖起步里程内、超里程、时长费。

预期：计算公式、舍入、规则 ID/快照正确；金额不为负。

### T-ADMIN-PRICE-10 边界

测试 0 距离、恰好起步里程、极大里程/时长、无匹配规则。

预期：边界计算稳定；无规则明确失败；不发生 BigDecimal 精度漂移。

## 4. 优惠券模板查询

### T-ADMIN-COUPON-01 规则下模板列表

调用 `/admin/api/v1/pricing/fare-rules/{id}/coupons`。

预期：仅返回与该规则 `companyId + cityCode + productCode` 同范围模板；fare_rule 本身不新增 coupon 字段。

### T-ADMIN-COUPON-02 数据域只读

省管/市操作员查询域内和域外规则优惠券。

预期：域内可读；域外 404；列表 total 不泄露其它域模板。

## 5. 模板生命周期

### T-ADMIN-COUPON-03 创建固定减免模板

SUPER 创建 AMOUNT_OFF。

预期：状态 DRAFT；门槛、减免额、totalCount、perUserLimit、有效期和范围正确；减免不能导致设计上的非法负应付。

### T-ADMIN-COUPON-04 创建比例/封顶模板

验证 discountRate、maxDiscountAmount。

预期：折扣率范围和封顶约束生效；字段组合与 couponType 匹配，不能同时提交互斥规则。

### T-ADMIN-COUPON-05 更新 DRAFT

预期：允许字段更新；已发布核心金额规则不得无审计任意修改。

### T-ADMIN-COUPON-06 发布和下架

执行 `DRAFT -> PUBLISHED -> OFFLINE`。

预期：只有合法前序状态可变更；发布时间/操作人记录；下架后不可新领取，但已领取券按快照和正式规则处理。

### T-ADMIN-COUPON-07 非 SUPER 写接口

省管/市操作员调用创建、更新、发布、下架。

预期：403；下游无数据变化。

### T-ADMIN-COUPON-08 并发发布/下架

预期：状态条件更新保证一次有效变更；不重复扣发放库存或写冲突状态。

## 6. 领取与库存

### T-ADMIN-COUPON-09 发布后可领取

乘客查询 claimable 并领取。

预期：生成 user_coupon，保存模板关键快照；模板未发布、未生效、过期或领完时不可领。

### T-ADMIN-COUPON-10 总量和每人上限

并发领取最后一张券、同用户重复领取。

预期：totalCount 不超发；perUserLimit 生效；手机号哈希身份防止注销重注册重复领取。

## 7. 优惠计算

### T-ADMIN-COUPON-11 固定减免门槛

金额低于、等于、高于 threshold。

预期：低于不可用；达到后减免准确；payableAmount 最低为 0。

### T-ADMIN-COUPON-12 比例和封顶

用多个订单金额测试。

预期：折扣金额按 BigDecimal 规则计算；超过 maxDiscount 时封顶；两位小数舍入一致。

### T-ADMIN-COUPON-13 最优券排序

同用户同时持有多张可用券。

预期：先按本单实际优惠额降序；相同优惠优先快过期，再按 userCouponId 小者；每单最多一张。

### T-ADMIN-COUPON-14 最终承运车队

预估候选属于 C1、最终司机属于 C2。

预期：最终可用券只按 `trip_order.companyId=C2`；不得锁定 C1 券。

### T-ADMIN-COUPON-15 锁定并发

同一 user_coupon 并发锁给两单。

预期：最多一单成功；lockedOrderNo 唯一；另一单选择其它券或失败。

## 8. 金额和当前实现边界

### T-ADMIN-COUPON-16 服务费快照计算

示例 finalAmount=35、discount=5。

预期目标：payable=30、平台服务费=1.50、承运侧收入=28.50。

注意：司机 finish 后的结算编排已在本地 mock MVP 中贯通：冻结计价规则，按稳定 mock 距离/预计时长/实际时长计价，锁定优惠券并按优惠后金额支付。联调应通过完单和结算查询验证，不得只以表结构或内部接口存在作为端到端通过依据；真实支付、退款、对账及分成仍不在本用例范围。

### T-ADMIN-COUPON-17 支付失败/退款边界

当前支付失败、取消或结果待确认时优惠券保持 `LOCKED`，支付成功后才核销；支付明确失败没有后台定时自动重试，乘客可主动支付。真实退款流程未实现，不执行“退款闭环已通过”结论。

## 9. 自动化回归

```bash
mvn -pl calculate test
mvn -pl admin-api test
```

当前 `admin-api` 测试覆盖较弱，应额外保留不同角色响应、模板/用户券/用券流水和结算快照证据。
