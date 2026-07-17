# 乘客端登录 TEST

对应《乘客端_登录_PRD.md》《乘客端_登录_API.md》《乘客端_登录_TECH.md》。所有外部请求统一经 `http://127.0.0.1:18080`。

## 0. 测试目标

- 短信验证码登录即注册。
- 密码登录与失败风控。
- JWT `sub/aud/tv/exp` 正确。
- 网关身份注入和跨端隔离。
- 登出后 HTTP token、WS 小票同步失效。

## 1. 环境与数据

- 启动 gateway、passenger-api、passenger、Redis、MySQL。
- 本地短信 mock 开启时记录响应/日志中的验证码。
- 准备：未注册手机号 A、已注册有密码手机号 B、已注册无密码手机号 C、冻结或不可用账号 D。
- 清理 Redis 测试键时只清对应手机号：`app:otp:*`、`app:sms:gap:*`、`app:login:fail:*`、`app:login:ban:*`。

## 2. 短信发送

### T-PA-AUTH-01 首次发送成功

步骤：调用 `POST /app/api/v1/auth/sms/send`，body 传合法手机号 A。

预期：

- 返回 `code=200`。
- Redis `app:otp:{phone}` 存在 6 位验证码，TTL 不超过 300 秒。
- `app:sms:gap:{phone}` 存在，TTL 约 60 秒。
- 日计数键递增。

### T-PA-AUTH-02 60 秒内重复发送

立即对同一手机号再次发码。

预期：返回频率限制错误；旧 OTP 不应被意外无限延长；不同手机号不受该 gap 键影响。

### T-PA-AUTH-03 手机号格式与日上限

测试空值、位数错误、非数字、达到日发送上限。

预期：参数错误为 400；达到上限后拒绝继续发送，不新增可用 OTP。

## 3. 短信登录

### T-PA-AUTH-04 新手机号登录即注册

步骤：为手机号 A 发码，再调用 `/login-sms`。

预期：

- 创建一条有效 customer，手机号正确、`is_deleted=0`。
- 返回 accessToken、过期秒数和用户信息。
- JWT `sub=customer.id`、`aud=app-bff`，含 `tv`、`iat`、`exp`。
- OTP 校验成功后被消费，重复使用同验证码失败。

### T-PA-AUTH-05 已注册手机号短信登录

手机号 B 使用正确验证码登录。

预期：复用原 customer.id，不重复建账号；新登录生成当前有效 token version，旧会话按单会话规则失效。

### T-PA-AUTH-06 错误与过期验证码

分别使用错误验证码、删除 OTP 模拟过期、把 A 的验证码用于 B。

预期：均失败；不创建新账号；失败计数递增。达到封禁阈值后当天登录被拒绝。

## 4. 密码登录

### T-PA-AUTH-07 正确密码

调用 `/login-password` 登录手机号 B。

预期：返回与短信登录相同结构的 app token；数据库密码哈希不出现在响应和日志。

### T-PA-AUTH-08 错误密码与无密码账号

手机号 B 使用错误密码；手机号 C 使用任意密码。

预期：错误密码返回 401/业务登录失败；无密码账号提示使用验证码登录；两者均不签发 token。

### T-PA-AUTH-09 不可用账号

使用冻结、禁用或已注销账号 D 登录。

预期：不能获取有效登录态；错误消息不能泄露密码或内部状态字段。

## 5. Token 与身份

### T-PA-AUTH-10 受保护接口

用有效 appToken 调用 `/app/api/v1/settings/profile` 或本人订单查询。

预期：网关注入的 `X-User-Id` 等于 JWT `sub`；客户端无需也不应传 passengerId。

### T-PA-AUTH-11 伪造用户 ID

携带用户 A token，同时传 `X-User-Id` 或 body/query passengerId=B。

预期：网关覆盖伪造头；涉及 body/query 身份的接口应拒绝不一致值，不能读取 B 数据。

### T-PA-AUTH-12 跨端 token

使用 appToken 访问 `/driver/**`、`/admin/**`。

预期：网关按 aud 拒绝；请求不进入对应 BFF。

## 6. 登出与 WS

### T-PA-AUTH-13 WS 小票

有效 appToken 调用 `POST /app/api/v1/auth/ws-token`。

预期：返回短期 `audit=2` 小票；小票能连接 `/app/ws/v1/stream`，但不能作为普通 HTTP Bearer 使用。

### T-PA-AUTH-14 登出

步骤：保存 HTTP token 和 WS 小票，调用 `/logout`，再重复访问业务接口与 WS 握手。

预期：

- Redis `passenger:tv:{customerId}` 递增。
- 旧 HTTP token 返回 401。
- 旧 WS 小票握手失败或已有连接关闭。
- 再次登录可获得新 token。

### T-PA-AUTH-15 登出联动订单

分别准备等待态、司机已到达后的订单执行登出。

预期：订单联动严格按《第一期MVP_乘客派单司机闭环_TEST.md》执行；到达后不得用登出绕过状态机取消行程。

## 7. 并发与安全

### T-PA-AUTH-16 并发短信登录

同一 OTP 并发提交两次。

预期：不得创建两个有效 customer；OTP 最多成功消费一次，账号唯一约束兜底。

### T-PA-AUTH-17 敏感信息日志

检查正常和失败日志。

预期：不打印明文密码、完整 JWT、生产验证码；手机号按项目日志策略脱敏。

## 8. 回归证据

至少保存接口响应、解码后的非敏感 JWT claims、Redis TTL、customer 行和登出前后 token 结果。乘客认证目前缺专项单元测试，改动认证逻辑后必须执行本文用例。
