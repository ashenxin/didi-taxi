# 司机端登录注册 TEST

对应《司机端_登录注册_PRD.md》《司机端_登录注册_API.md》《司机端_登录注册_TECH.md》。本文聚焦注册、登录、单会话、WS 握手和登出清理；订单状态回归见《第一期MVP_乘客派单司机闭环_TEST.md》与《订单与派单_TEST.md》。

## 0. 测试目标

- 短信/密码注册和登录。
- 审核状态只限制听单/WS，不应与基础登录语义混淆。
- `driver:tv:{driverId}` 单会话生效。
- 登出按顺序释放指派和到达前订单、清理 GEO/Presence、作废 token。

## 1. 环境与数据

- 启动 gateway、driver-api、capacity、order、Redis、MySQL；登出重派测试还需 Kafka。
- 准备未注册手机号 A、已注册已审核司机 B、已注册待审核司机 C、不可用司机 D。
- 司机 B 配置有效 `cityCode`、经纬度、车辆和 `can_accept_order=1`。
- 仅清理测试手机号相关 Redis 键：`driver:otp:*`、`driver:sms:gap:*`、`driver:login:fail:*`、`driver:login:ban:*`、`driver:tv:*`。

## 2. 短信与注册

### T-DR-AUTH-01 发送验证码

调用 `POST /driver/api/v1/auth/sms/send`。

预期：`driver:otp:{phone}` TTL 约 300 秒；发送间隔键约 60 秒；重复发码与日上限生效。

### T-DR-AUTH-02 短信注册

手机号 A 使用正确验证码调用 `/register-sms`。

预期：

- capacity 创建唯一司机账号，初始审核/接单状态符合 API 文档。
- 返回 `aud=driver-bff`、`sub=driver.id`、含 `tv` 的 token。
- OTP 被消费，重复验证码不能再次注册。

### T-DR-AUTH-03 密码注册

新手机号完成验证码校验后调用 `/register-password`。

预期：密码只保存强哈希；响应不返回 hash；后续可密码登录。

### T-DR-AUTH-04 重复账号与非法参数

测试重复手机号、错误/过期验证码、空密码、弱密码、非法手机号。

预期：返回 400/409/登录失败类业务码；不产生重复司机或半成品车辆归属。

## 3. 登录

### T-DR-AUTH-05 短信和密码登录

司机 B 分别使用两种方式登录。

预期：返回相同形状的登录结果；JWT claims 正确；第二次登录推进 tv，第一次 token 在 driver-api 侧失效。

### T-DR-AUTH-06 错误凭据与封禁

连续提交错误密码和验证码。

预期：共用当日失败计数；达到阈值写 `driver:login:ban:{yyyyMMdd}:{phone}`；封禁期内正确凭据也按风控规则拒绝。

### T-DR-AUTH-07 待审核司机

司机 C 登录。

预期：可按产品口径登录并查看审核原因/资料；不能上线听单、领取指派或建立业务 WS。

### T-DR-AUTH-08 不可用司机

司机 D 登录或调用业务接口。

预期：无法获得可用业务会话；错误不泄露密码、证件或内部审核数据。

## 4. 身份与单会话

### T-DR-AUTH-09 `X-User-Id` 与 driverId

用司机 B token 调用 `/drivers/{driverId}/listening-status`，分别传本人和他人 ID。

预期：本人成功；他人返回 403；客户端伪造 `X-User-Id` 被网关覆盖。

### T-DR-AUTH-10 tv 不一致

人工递增 `driver:tv:{driverId}` 后使用旧 token。

预期：网关可能通过签名/aud，但 driver-api 鉴权过滤器返回 401；Controller 不执行业务动作。

### T-DR-AUTH-11 跨端 token

driverToken 访问 `/app/**`、`/admin/**`。

预期：网关按 aud 拒绝。

## 5. WebSocket

### T-DR-AUTH-12 换取小票并握手

步骤：

1. 已审核司机 B 登录。
2. 调用 `POST /driver/api/v1/auth/ws-token`。
3. 连接 `/driver/ws/v1/stream?token=<wsToken>`。

预期：小票为 `audit=2` 且短期有效；握手 101；服务端 session 绑定 driverId；可收 PONG/`ASSIGNED_LIST`。

### T-DR-AUTH-13 非法 WS 小票

测试 HTTP token 直接握手、app 小票、过期小票、tv 已变化的小票、待审核司机小票。

预期：全部不能建立有效司机业务会话。

### T-DR-AUTH-14 新连接顶旧连接

同一司机建立第二条 WS。

预期：旧连接关闭，新连接保留；旧连接的 close 回调不得把新连接对应司机误下线。

### T-DR-AUTH-15 心跳超时

停止发送心跳直至超过服务端阈值。

预期：会话被清理并调用下线链路；DB `monitor_status`、GEO 和 Presence 最终一致。

## 6. 登出清理

### T-DR-AUTH-16 无订单登出

司机上线后直接调用 `/auth/logout`。

预期：

- capacity `monitor_status=0`。
- 城市 GEO 中不存在 driverId，Presence 被移除。
- `driver:tv:{driverId}` 递增。
- 旧 HTTP token/WS 小票失效。

### T-DR-AUTH-17 有待确认指派登出

准备 `ASSIGNED/PENDING_DRIVER_CONFIRM` 订单后登出。

预期：逐笔复用 reject，原因 `DRIVER_LOGOUT`；订单回 `CREATED` 并重新产生派单 Outbox；司机-乘客隔离规则按主动拒单链路执行；列表不再出现旧指派。

### T-DR-AUTH-18 有到达前 ACCEPTED 订单登出

预期：复用司机到达前取消/释放链路，订单回 `CREATED` 重派，不形成乘客侧 `CANCELLED` 终态。

### T-DR-AUTH-19 到达后登出

准备 `ARRIVED/STARTED` 订单后登出。

预期：不能释放或取消在途订单；仍需作废登录态并按实现处理听单状态；日志应明确存在在途订单。

### T-DR-AUTH-20 部分释单失败

模拟 order 服务某一笔 reject/释放失败。

预期：记录包含 orderNo/driverId 的错误；后续下线和 token 作废仍执行，不能让失败请求保留有效登录态。

## 7. 并发与安全

### T-DR-AUTH-21 并发登录/登出

并发执行两个登录和一个登出。

预期：最终只认 Redis 当前 tv；较旧 token 全部失效；不会出现 tv 回退。

### T-DR-AUTH-22 敏感信息

检查日志和响应：不得包含明文密码、完整 JWT、生产 OTP、证件号或证件图片地址等无关敏感数据。

## 8. 自动化回归

```bash
mvn -pl driver-api test
mvn -pl capacity test
```

重点现有测试：`DriverBffServiceLogoutTest`、`DriverNoticeWebSocketHandlerTest`、`DriverWsHandshakeInterceptorTest`。自动化通过后仍需执行 GEO/Presence/Kafka 端到端用例。
