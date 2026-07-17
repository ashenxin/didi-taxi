# 后台管理系统：订单管理 TEST

对应订单管理 PRD/API/TECH。当前可执行范围为订单列表、详情和事件时间线；后台“派单诊断聚合接口/页面”尚未实现，不能记为已通过。

## 0. 环境与数据

- 启动 gateway、admin-api、order、passenger。
- 准备 SUPER、浙江省管、杭州/宁波市操作员。
- 准备不同省市、不同状态、不同乘客手机号、跨日期的订单。
- 至少一单包含创建、指派、拒单、重派、接单、到达、开始、完单事件。

## 1. 列表

### T-ADMIN-ORDER-01 默认分页

调用 `GET /admin/api/v1/orders`。

预期：pageNo/pageSize 默认值正确；列表按约定时间倒序；total/list/page 信息一致；列表不返回敏感 passengerId。

### T-ADMIN-ORDER-02 订单号精确查询

传完整 orderNo。

预期：只返回该订单；同时传错误 phone 时按当前契约不再执行手机号换 passengerId，避免错误交叉筛选。

### T-ADMIN-ORDER-03 手机号查询

传存在和不存在手机号。

预期：存在手机号只查对应 passengerId；不存在手机号直接返回空列表，不向 order 传 null 造成全量查询。

### T-ADMIN-ORDER-04 状态、时间和省市组合

分别及组合传 status、startTime/endTime、provinceCode/cityCode。

预期：交集过滤准确；起止时间边界明确；非法状态、开始晚于结束返回 400。

### T-ADMIN-ORDER-05 分页边界

测试 pageNo=0、负数、pageSize=0、超大 pageSize、超末页。

预期：非法参数拒绝或按统一上限裁剪；超末页返回空 list、total 保持正确；不得 OOM。

### T-ADMIN-ORDER-06 列表防 N+1

查询一页多条订单，观察 admin-api 调用 passenger 次数。

预期：列表不为每行补手机号；不得出现按行调用 passenger 的 N+1。

## 2. 数据域

### T-ADMIN-ORDER-07 省管和市操作员列表

按《后台管理系统_权限_TEST.md》执行省市组合。

预期：不传筛选也自动收窄；越界筛选 403；total 不含它域订单。

### T-ADMIN-ORDER-08 跨域详情

杭州账号访问宁波 orderNo。

预期：404，响应不暴露订单属于宁波。

## 3. 详情与事件

### T-ADMIN-ORDER-09 正常详情

调用 `GET /admin/api/v1/orders/{orderNo}`。

预期：订单核心字段、乘客脱敏/展示手机号、司机/公司、金额和取消信息与 DB 一致；详情补乘客信息只调用一次。

### T-ADMIN-ORDER-10 不存在订单

预期：404；不得返回 `code=200,data=null` 混淆前端。

### T-ADMIN-ORDER-11 事件时间线

预期：事件按 occurredAt、id 稳定升序；相同时间不乱序；eventType、from/to status、reasonCode 与订单动作对应。

### T-ADMIN-ORDER-12 异常 payload

准备 payload 为空或历史 JSON 字段不完整的事件。

预期：详情仍可展示基本事件；单条 payload 解析失败不应让整个订单详情 500，除非当前契约明确失败。

## 4. 下游异常

### T-ADMIN-ORDER-13 order 不可用

预期：admin-api 返回明确 502/下游错误；不缓存或返回旧订单假成功。

### T-ADMIN-ORDER-14 passenger 不可用

列表查询应不受手机号补全影响；手机号筛选或详情补手机号失败时按 BFF 契约返回明确错误，日志含 traceId。

## 5. 派单诊断边界

### T-ADMIN-ORDER-15 当前未实现入口

检查 admin-api Controller。

预期：目前只有列表/详情；文档建议的诊断聚合路径不作为当前验收通过项。order/capacity 内部诊断按《订单与派单_TEST.md》验证。

诊断接口落地后至少补：SUCCESS、NO_DRIVER、FAILED、INVALID/MALFORMED、无 Outbox、FAILED retry 权限和重复 retry。

## 6. 非功能

### T-ADMIN-ORDER-16 大分页和并发查询

多管理员并发执行复杂筛选。

预期：响应时间和 DB 连接稳定；数据域不会因并发上下文串号；日志能按 userId/traceId/orderNo 定位。

## 7. 验收证据

保留请求参数、响应、order/passenger 下游调用日志、数据域账号 claims、订单和事件表快照。

## 8. 2026-07-17 实测结果

- 后台订单列表加载 8 条测试数据，正常展示已完成和已取消订单。
- 订单 `202607170951274283210` 详情、乘客手机号、司机/车辆/公司、预估与最终金额、各时间节点均可展示。
- 事件按发生时间升序展示，中文名称覆盖创建、派单、等待司机确认、司机确认超时、接单、到达、开始和结束行程。
- `PENDING_DRIVER_CONFIRM(7)` 已显示为“待司机确认”；创建事件无前置状态时显示 `- → 已创建`，不再误显示“未知”。
- 未识别的新事件类型会保留原始 `eventType`，避免统一降级成“其他事件”而丢失排障信息。
- 管理后台浏览器控制台无 error/warning；`/capacity/cars` 重复动态路由告警已消除。
