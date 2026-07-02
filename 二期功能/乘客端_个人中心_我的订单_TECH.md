# 乘客端个人中心「我的订单」二期 TECH

> 本文档描述「我的订单」列表页的后端实现方式、接口编排与当前边界。
> 产品口径见《乘客端_个人中心_我的订单_PRD.md》，接口契约见《乘客端_个人中心_我的订单_API.md》。

---

## 1. 实现边界

- 该需求当前由 `passenger-api` 承担
- `passenger-api` 作为乘客端 BFF，负责：
  - 鉴权
  - 订单列表聚合
  - 列表项展示字段组装
  - 按页面需要补充按钮占位
- `order-service` 继续作为订单权威来源

---

## 2. 复用链路

### 2.1 底层分页查询

当前底层已存在：

- `order-service GET /api/v1/orders`

该接口支持：

- `passengerId`
- `pageNo`
- `pageSize`
- 按 `created_at` 倒序

因此本期没有新增订单核心查询，只是在 `passenger-api` 里做聚合与筛选。

### 2.2 乘客列表接口

`passenger-api` 新增：

- `GET /app/api/v1/orders`

实现思路：

1. 读取当前乘客 `X-User-Id`
2. 调用 `order-service` 分页拉取乘客全部订单
3. 按页面筛选类型过滤
4. 返回分页结构和列表卡片数据

---

## 3. 数据结构

### 3.1 列表分页

返回：

- `PassengerOrderPageVO`

字段：

- `list`
- `total`
- `pageNo`
- `pageSize`
- `type`

### 3.2 列表项

返回：

- `PassengerOrderListItemVO`

字段包括：

- 订单号
- 起终点地址
- 当前状态
- 预估/实付金额
- 司机摘要
- 时间戳
- 取消信息
- `reDispatching`
- `actions`

### 3.3 按钮占位

返回：

- `PassengerOrderActionVO`

本期固定返回 3 项：

- `APPLY_INVOICE`
- `RETURN_TRIP`
- `RATE`

当前全部：

- `disabled = true`
- `implemented = false`

---

## 4. 筛选映射

### 4.1 订单类型

当前支持：

- `ALL`
- `TO_DEPART`
- `REFUND_CANCEL`

也兼容中文参数：

- `全部`
- `待出发`
- `退款与取消`

### 4.2 状态归并

当前实现里：

- `TO_DEPART` -> `CREATED / ASSIGNED / PENDING_DRIVER_CONFIRM / ACCEPTED / ARRIVED`
- `REFUND_CANCEL` -> `CANCELLED`

说明：

- 这是当前仓库的实现口径
- 若后续退款状态单独落库，可继续扩展 `REFUND_CANCEL`

---

## 5. 关键实现点

### 5.1 倒序

- 以 `created_at` 倒序展示
- 不在 BFF 再做二次排序，直接沿用订单核心返回顺序

### 5.2 分页

- BFF 侧按页面参数进行分页切片
- 当前实现为了保证筛选后列表可用，会先拉取乘客订单，再做筛选分页
- 适用于本期二期小流量/联调场景

### 5.3 重新派单标记

- 对 `CREATED` 状态订单继续查询订单事件
- 若事件中包含司机拒单、到达前取消、确认窗超时等事件，则 `reDispatching=true`
- 该标记用于页面文案展示“正在重新派单”

---

## 6. 已验证内容

- `passenger-api` 单测通过
- 新增的列表接口已在测试中覆盖：
  - 全部订单
  - 待出发筛选
  - 退款与取消筛选
  - 按钮占位
  - 控制器参数传递

