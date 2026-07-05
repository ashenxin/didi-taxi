# 乘客端个人中心「设置」二期 TECH

> 本文档描述「设置」功能的后端实现方式、接口编排、数据一致性和风险处理。
> 产品口径见《乘客端_个人中心_设置_PRD.md》，接口契约见《乘客端_个人中心_设置_API.md》。

---

## 1. 实现边界

- 「设置」由 `passenger-api` 作为乘客端 BFF 对外承接。
- `passenger-api` 负责：
  - JWT 鉴权后的当前乘客身份识别
  - 设置页接口编排
  - token version 失效处理
  - 调用 `passenger` 核心服务修改账号
  - 调用 `order-service` 校验注销前是否存在进行中订单
- `passenger` 核心服务负责：
  - `customer` 表读取与更新
  - 更换手机号唯一性校验
  - 逻辑删除账号
  - 设置业务验证码生成、存储、校验
- `order-service` 继续作为订单权威来源。

---

## 2. 复用链路

### 2.1 当前登录身份

现有链路：

- `AppJwtService` 签发 JWT，`sub=customerId`
- `PassengerJwtAuthFilter` 校验 JWT 签名、aud、token version、audit
- 校验通过后注入 `X-User-Id`

设置功能继续复用该链路。

关键口径：

- 更换手机号不依赖前端传入旧手机号。
- 服务端只信任 `X-User-Id` 对应的 `customer.id`。
- 当前手机号从数据库查询获得。

### 2.2 token version

现有 `PassengerTokenVersionStore` 支持：

- 登录时递增 token version
- 登出时递增 token version
- 请求时校验 JWT 中 `tv` 与 Redis 当前值一致

本期新增：

- 更换手机号成功后递增 token version
- 注销账号成功后递增 token version

这样可以使当前 HTTP token 与 WebSocket token 立即失效。

### 2.3 订单归属

订单表使用 `trip_order.passenger_id` 关联 `customer.id`。

因此：

- 更换手机号只更新 `customer.phone`，不影响历史订单。
- 注销账号不删除订单，不迁移订单。
- 同手机号重新注册后得到新 `customer.id`，不会继承旧账号订单。

---

## 3. 数据模型

### 3.1 customer 表

关键字段：

- `id`：乘客真实身份主键
- `phone`：登录手机号，可变
- `is_deleted`：逻辑删除标识
- `phone_active`：生成列，`is_deleted=0` 时等于 `phone`，否则为 `NULL`

唯一约束：

- `uk_customer_phone_active(phone_active)`

影响：

- 未注销账号手机号唯一。
- 注销后 `phone_active=NULL`，手机号释放，可重新注册。
- 重新注册会插入新 `customer.id`。

### 3.2 trip_order 表

关键字段：

- `passenger_id`：关联 `customer.id`

影响：

- 订单归属不受手机号变化影响。
- 注销后旧订单仍关联旧 `customer.id`。
- 新账号不会查询到旧账号订单。

---

## 4. 验证码设计

### 4.1 独立业务 key

设置功能不复用登录验证码 key。

建议 Redis key：

- 更换手机号新手机号验证码：`app:settings:phone-change:new:otp:{customerId}:{newPhone}`
- 更换手机号发送间隔：`app:settings:phone-change:sms:gap:{customerId}:{newPhone}`
- 更换手机号日计数：`app:settings:phone-change:sms:daily:{customerId}:{newPhone}:{yyyy-MM-dd}`
- 注销账号验证码：`app:settings:account-cancel:otp:{customerId}`
- 注销账号发送间隔：`app:settings:account-cancel:sms:gap:{customerId}`
- 注销账号日计数：`app:settings:account-cancel:sms:daily:{customerId}:{yyyy-MM-dd}`

### 4.2 频控

可复用现有短信配置：

- 同一场景最小发送间隔
- 同一手机号或同一账号自然日发送上限
- 验证码 TTL

说明：

- 更换手机号按 `customerId + newPhone` 维度控制。
- 注销账号按 `customerId` 维度控制。

### 4.3 mock 发送

本地联调可以继续沿用 mock 短信能力：

- mock 开启时返回 `mockCode`
- 生产环境返回 `null`

---

## 5. 接口编排

### 5.1 查询设置摘要

接口：

- `GET /app/api/v1/settings/profile`

流程：

1. `passenger-api` 从 `X-User-Id` 获取 `customerId`。
2. 调用 `passenger` 核心服务按 ID 查询未注销账号。
3. 返回手机号脱敏信息。

### 5.2 发送新手机号验证码

接口：

- `POST /app/api/v1/settings/phone-change/sms/send`

流程：

1. 校验登录态，获取 `customerId`。
2. 查询当前账号，确认未注销。
3. 校验 `newPhone` 格式。
4. 如果 `newPhone` 等于当前手机号，返回 400。
5. 查询是否存在 `is_deleted=0` 且 `phone=newPhone` 的账号。
6. 若存在，返回 409。
7. 写入独立验证码 key。
8. 返回发送结果。

### 5.3 提交更换手机号

接口：

- `POST /app/api/v1/settings/phone-change/confirm`

流程：

1. 校验登录态，获取 `customerId`。
2. 查询当前账号，确认未注销。
3. 校验 `newPhone` 格式。
4. 校验新手机号验证码。
5. 再次查询新手机号是否已被未注销账号占用。
6. 在事务内更新当前 `customer.id` 这一行的 `phone`。
7. 删除或消费验证码 key。
8. `passenger-api` 递增 token version。
9. 返回 `requireLogin=true`。

关键点：

- 不创建新 `customer`。
- 不迁移订单。
- 不返回新 token。

### 5.4 发送注销验证码

接口：

- `POST /app/api/v1/settings/account-cancel/sms/send`

流程：

1. 校验登录态，获取 `customerId`。
2. 查询当前账号，确认未注销。
3. 获取当前绑定手机号。
4. 写入注销场景独立验证码 key。
5. 向当前绑定手机号发送验证码。
6. 返回发送结果和脱敏手机号。

### 5.5 提交注销账号

接口：

- `POST /app/api/v1/settings/account-cancel/confirm`

流程：

1. 校验登录态，获取 `customerId`。
2. 校验 `confirm=true`。
3. 查询当前账号，确认未注销。
4. 校验注销验证码。
5. 调用 `order-service` 查询当前乘客全部订单或非终态订单。
6. 若存在进行中订单，返回 409。
7. 在事务内更新当前 `customer.id` 这一行 `is_deleted=1`。
8. 删除或消费验证码 key。
9. `passenger-api` 递增 token version。
10. 返回 `requireLogin=true`。

---

## 6. 进行中订单判断

### 6.1 当前状态口径

订单状态机：

- `0 CREATED`
- `1 ASSIGNED`
- `7 PENDING_DRIVER_CONFIRM`
- `2 ACCEPTED`
- `3 ARRIVED`
- `4 STARTED`
- `5 FINISHED`
- `6 CANCELLED`

注销前禁止存在所有非终态订单。

终态：

- `FINISHED`
- `CANCELLED`

进行中：

- `CREATED`
- `ASSIGNED`
- `PENDING_DRIVER_CONFIRM`
- `ACCEPTED`
- `ARRIVED`
- `STARTED`
- 其他未知非空状态按进行中处理

### 6.2 查询策略

二期可复用当前 `PassengerOrderService` 中加载乘客全部订单的思路：

1. 按 `passengerId` 分页查询订单。
2. 遍历状态。
3. 发现非终态立即拒绝注销。

后续可优化为 order-service 提供轻量接口：

- `GET /api/v1/orders/active-count?passengerId=`

---

## 7. 一致性与并发

### 7.1 更换手机号并发

风险：

- 两个账号同时尝试换绑同一新手机号。

处理：

- 发送验证码前校验唯一性。
- 提交时再次校验唯一性。
- 数据库 `uk_customer_phone_active` 兜底。
- 捕获唯一键冲突并返回 409。

### 7.2 注销与下单并发

风险：

- 注销校验通过后，下单请求并发创建新订单。

处理：

- 注销成功后立即递增 token version，使旧请求失效。
- 注销提交时校验订单状态。
- 后续实现可在下单前补充账号未注销校验，降低并发窗口。

### 7.3 旧 token 失效

更换手机号和注销账号成功后必须：

- 递增 token version
- 前端清理本地 token
- WebSocket token 在下一次校验或重连时失效

---

## 8. 前端实现要点

### 8.1 页面层级

- 个人中心内新增与「我的订单」平级的「设置」入口。
- 设置页面展示：
  - 当前手机号脱敏信息
  - 更换手机号入口
  - 注销账号入口

### 8.2 更换手机号

- 不展示旧手机号输入框。
- 展示当前绑定手机号脱敏信息。
- 输入新手机号。
- 获取新手机号验证码。
- 提交成功后清除 token，回登录页。

### 8.3 注销账号

- 展示风险说明。
- 获取当前绑定手机号验证码。
- 用户必须完成二次确认。
- 注销成功后清除 token，回登录页。

---

## 9. 待开发清单

- `passenger-api` 新增设置 Controller / Service。
- `passenger-api` 新增调用 `passenger` 核心服务的 Feign 接口。
- `passenger` 新增设置类账号服务接口。
- `passenger` 新增更换手机号、注销账号 DTO。
- `passenger` 新增设置场景验证码 key 与频控。
- `passenger-api` 注销前接入订单非终态校验。
- 乘客 H5 个人中心新增「设置」入口与页面。
- 补充后端单测和前端基础回归。

---

## 10. 当前实现状态

- 本文档为待开发技术设计。
- 具体类名、方法名可在开发时按仓库现有命名风格落地。

