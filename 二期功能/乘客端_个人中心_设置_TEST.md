# 乘客端个人中心「设置」二期 TEST

> 本文档用于「设置」功能的验收回归。
> 接口见《乘客端_个人中心_设置_API.md》，产品口径见《乘客端_个人中心_设置_PRD.md》。

---

## 0. 测试范围

- 个人中心展示与「我的订单」平级的「设置」入口
- 设置页展示当前绑定手机号脱敏信息
- 更换手机号
  - 校验登录态
  - 发送新手机号验证码
  - 校验新手机号唯一性
  - 更换后 `customer.id` 不变
  - 更换后历史订单保留
  - 更换后当前登录态失效
- 注销账号
  - 发送当前绑定手机号验证码
  - 有进行中订单时禁止注销
  - 无进行中订单时逻辑删除账号
  - 注销后当前登录态失效
  - 注销后同手机号可重新注册为新账号
  - 新账号不继承旧订单

---

## 1. 环境准备

- `gateway`、`passenger-api`、`passenger`、`order` 可用
- Redis 可用
- 乘客账号 A 可登录
- 乘客账号 A 至少有一笔历史终态订单
- 准备一个未注册手机号 B
- 准备一个已注册且未注销手机号 C
- mock 短信开启时，可从接口响应或日志获取验证码

---

## 2. 接口测试：设置摘要

### T-SET-01 查询设置摘要

- **接口**：`GET /app/api/v1/settings/profile`
- **前置**：账号 A 已登录
- **预期**
  - 返回 `customerId`
  - 返回脱敏手机号
  - `deleted=false`

### T-SET-02 未登录查询设置摘要

- **接口**：`GET /app/api/v1/settings/profile`
- **前置**：不带 token
- **预期**
  - 返回 401
  - 不返回账号信息

---

## 3. 接口测试：更换手机号

### T-SET-03 发送新手机号验证码成功

- **接口**：`POST /app/api/v1/settings/phone-change/sms/send`
- **请求体**：`{ "newPhone": "手机号B" }`
- **前置**：账号 A 已登录；手机号 B 未注册
- **预期**
  - 返回 200
  - mock 环境返回 `mockCode`
  - Redis 写入更换手机号独立验证码 key

### T-SET-04 新手机号已被占用时发送失败

- **接口**：`POST /app/api/v1/settings/phone-change/sms/send`
- **请求体**：`{ "newPhone": "手机号C" }`
- **前置**：手机号 C 属于未注销账号
- **预期**
  - 返回 409
  - 提示“该手机号已被使用”
  - 不发送验证码

### T-SET-05 新手机号与当前手机号相同时发送失败

- **接口**：`POST /app/api/v1/settings/phone-change/sms/send`
- **请求体**：`{ "newPhone": "账号A当前手机号" }`
- **预期**
  - 返回 400
  - 提示新手机号不能与当前手机号相同

### T-SET-06 提交更换手机号成功

- **接口**：`POST /app/api/v1/settings/phone-change/confirm`
- **请求体**：`{ "newPhone": "手机号B", "code": "正确验证码" }`
- **前置**
  - 账号 A 已登录
  - 已向手机号 B 发送验证码
  - 账号 A 有历史订单
- **预期**
  - 返回 200
  - `changed=true`
  - `requireLogin=true`
  - `customer.id` 保持不变
  - `customer.phone` 更新为手机号 B
  - 原 token 再请求受保护接口返回 401
  - 使用手机号 B 重新登录后，仍能查到账号 A 原历史订单

### T-SET-07 验证码错误时更换失败

- **接口**：`POST /app/api/v1/settings/phone-change/confirm`
- **请求体**：`{ "newPhone": "手机号B", "code": "错误验证码" }`
- **预期**
  - 返回 401 或约定业务错误码
  - `customer.phone` 不变
  - token 不失效

### T-SET-08 提交时新手机号被并发占用

- **接口**：`POST /app/api/v1/settings/phone-change/confirm`
- **前置**
  - 已发送手机号 B 验证码
  - 提交前另一个账号已注册或换绑手机号 B
- **预期**
  - 返回 409
  - 账号 A 手机号不变

---

## 4. 接口测试：注销账号

### T-SET-09 发送注销验证码成功

- **接口**：`POST /app/api/v1/settings/account-cancel/sms/send`
- **前置**：账号 A 已登录
- **预期**
  - 返回 200
  - 验证码发送到账号 A 当前绑定手机号
  - 返回当前手机号脱敏信息
  - Redis 写入注销账号独立验证码 key

### T-SET-10 注销时未二次确认

- **接口**：`POST /app/api/v1/settings/account-cancel/confirm`
- **请求体**：`{ "code": "正确验证码", "confirm": false }`
- **预期**
  - 返回 400
  - 账号不注销

### T-SET-11 有进行中订单时禁止注销

- **接口**：`POST /app/api/v1/settings/account-cancel/confirm`
- **请求体**：`{ "code": "正确验证码", "confirm": true }`
- **前置**
  - 账号 A 有 `CREATED / ASSIGNED / PENDING_DRIVER_CONFIRM / ACCEPTED / ARRIVED / STARTED` 任一状态订单
- **预期**
  - 返回 409
  - 提示先完成或取消订单
  - `customer.is_deleted` 仍为 0
  - token 不失效

### T-SET-11A 有未结清订单或锁定券时禁止注销

- **前置**：账号没有进行中订单，但存在未结清 `trip_order_settlement`，或存在 `LOCKED` 优惠券。
- **预期**
  - 返回 409，账号不注销，token 保持有效。
  - order/calculate 任一检查接口不可用时返回 502，采用失败关闭，不得跳过检查。

### T-SET-12 无进行中订单时注销成功

- **接口**：`POST /app/api/v1/settings/account-cancel/confirm`
- **请求体**：`{ "code": "正确验证码", "confirm": true }`
- **前置**
  - 账号 A 没有进行中订单
  - 账号 A 只有 `FINISHED / CANCELLED` 订单或无订单
  - 不存在未结清订单结算或 `LOCKED` 优惠券
- **预期**
  - 返回 200
  - `cancelled=true`
  - `requireLogin=true`
  - `customer.is_deleted=1`
  - 原 token 再请求受保护接口返回 401
  - 后台/数据库仍可看到旧订单关联旧 `customer.id`
  - 旧账号 `UNUSED` 优惠券置为 `INVALID`
  - 福利积分余额清零并保留流水

### T-SET-13 注销验证码错误

- **接口**：`POST /app/api/v1/settings/account-cancel/confirm`
- **请求体**：`{ "code": "错误验证码", "confirm": true }`
- **预期**
  - 返回 401 或约定业务错误码
  - `customer.is_deleted` 不变
  - token 不失效

### T-SET-14 注销后同手机号重新注册

- **步骤**
  1. 账号 A 注销成功
  2. 使用账号 A 原手机号走短信登录/注册
- **预期**
  - 注册/登录成功
  - 生成新的 `customer.id`
  - 新 `customer.id` 不等于账号 A 的旧 `customer.id`
  - 新账号查询「我的订单」不返回旧账号订单

---

## 5. 页面验收

### T-SET-15 个人中心设置入口

- **步骤**
  1. 登录乘客端
  2. 进入个人中心
- **预期**
  - 可看到「设置」入口
  - 「设置」与「我的订单」属于平级入口

### T-SET-16 设置页展示

- **步骤**
  1. 点击「设置」
- **预期**
  - 展示当前手机号脱敏信息
  - 展示「更换手机号」
  - 展示「注销账号」

### T-SET-17 更换手机号页面

- **步骤**
  1. 进入「更换手机号」
- **预期**
  - 不要求输入旧手机号
  - 不要求旧手机号验证码
  - 展示当前手机号脱敏信息
  - 可输入新手机号和验证码
  - 有“更换后需重新登录”的提示

### T-SET-18 更换成功后的前端行为

- **步骤**
  1. 完成更换手机号
- **预期**
  - 展示成功提示
  - 清除本地 token
  - 跳转登录页
  - 使用新手机号可重新登录

### T-SET-19 注销页面风险提示

- **步骤**
  1. 进入「注销账号」
- **预期**
  - 展示注销影响说明
  - 展示后台保留历史数据说明
  - 展示手机号可重新注册但不继承旧订单说明
  - 要求短信验证码和二次确认

### T-SET-20 注销成功后的前端行为

- **步骤**
  1. 完成注销账号
- **预期**
  - 展示成功提示
  - 清除本地 token
  - 跳转登录页

---

## 6. 回归关注

- 登录、退出登录仍可用。
- 我的订单仍按 `customer.id` 查询。
- 更换手机号后历史订单不丢失。
- 注销后旧账号不可登录。
- 注销后新账号不继承旧订单。
- 设置验证码不影响登录验证码。

---

## 7. 回归命令

建议开发完成后至少执行：

- `mvn -pl passenger test`
- `mvn -pl passenger-api test`
- `npm run build`（目录：`didi-taxi-front/didi-passenger-h5`）
