# 网关服务 TEST

本文是 `gateway` 当前实现的专项回归文档，覆盖三端路由、CORS、JWT、可信身份头、错误响应和 WebSocket Upgrade。默认本地入口为 `http://127.0.0.1:18080`。

## 0. 测试范围与通过标准

覆盖模块：

- `/admin/** -> admin-api:8099`
- `/app/** -> passenger-api:8100`
- `/driver/** -> driver-api:8101`
- `JwtAuthenticationGlobalFilter`
- `StripSpoofedUserHeaderGlobalFilter`
- 全局 CORS 与 OPTIONS
- `/app/ws/**`、`/driver/ws/**` 握手转发

通过标准：网关不得把三端流量串路由；不得接受错端 token；不得信任客户端传入的 `X-User-Id`；鉴权错误和下游错误仍应返回浏览器可解析的响应。

## 1. 环境与测试数据

### 1.1 服务

- 启动 `gateway`。
- 路由测试至少启动一个目标 BFF；完整回归启动三个 BFF。
- WS 测试需启动 `passenger-api`、`driver-api` 和 Redis。

### 1.2 配置

鉴权回归：

```text
GATEWAY_JWT_REQUIRE_AUTH=true
JWT_SECRET_ADMIN / JWT_SECRET_APP / JWT_SECRET_DRIVER 与对应 BFF 一致
```

准备三枚未过期 JWT：

| token | aud | sub 示例 | 用途 |
|---|---|---:|---|
| adminToken | `admin-bff` | 1 | `/admin/**` |
| appToken | `app-bff` | 10001 | `/app/**` |
| driverToken | `driver-bff` | 80001 | `/driver/**` |

`GATEWAY_JWT_REQUIRE_AUTH=false` 只用于验证开发开关，不得代替正式鉴权回归。

## 2. 路由测试

### T-GW-01 三端正确转发

前置：三个 BFF 均启动。

步骤：

1. 携带 adminToken 请求 `GET /admin/api/v1/auth/me`。
2. 携带 appToken 请求任一 `/app/api/v1/**` 查询接口。
3. 携带 driverToken 请求任一 `/driver/api/v1/**` 查询接口。
4. 在 BFF 日志中确认请求到达的服务和原始路径。

预期：

- 三个请求只进入对应 BFF。
- 网关不去除 `/admin`、`/app`、`/driver` 前缀。
- 返回体保持下游 `code/msg/data` 结构。

### T-GW-02 下游不可用

步骤：停止 `passenger-api`，请求合法 `/app/**` 业务接口。

预期：

- 请求不得转发到其它 BFF。
- 返回 5xx/网关错误，不能误报业务成功。
- 响应中不得泄露 JWT 密钥、堆栈或内部连接串。

### T-GW-03 未匹配路径

请求 `/unknown/api`、拼错的 `/apps/**`。

预期：返回 404 或网关约定错误，不得落到任意核心服务。

## 3. CORS 测试

### T-GW-04 OPTIONS 预检

步骤：向三端各选一个 POST 路径发送 OPTIONS，请求包含：

```http
Origin: http://localhost:5173
Access-Control-Request-Method: POST
Access-Control-Request-Headers: authorization,content-type,idempotency-key
```

预期：

- 允许的 Origin、Method、Header 与 `gateway/application.yml` 一致。
- OPTIONS 不进入业务鉴权，不因缺 Bearer 返回 401。
- 只出现一组有效 CORS 响应头，不发生网关与 BFF 重复写头。

### T-GW-05 非法 Origin

使用未配置 Origin 重复预检和业务请求。

预期：浏览器侧不能获得允许跨域的响应头；服务端日志可定位 Origin，但不泄露安全配置。

### T-GW-06 鉴权失败响应仍带 CORS

用允许 Origin、不带 token 请求受保护接口。

预期：返回 401，同时保留必要 CORS 头，浏览器能够读取错误而不是只显示泛化 CORS failure。

## 4. JWT 与身份边界

### T-GW-07 公共白名单

不带 token 调用三端登录/发码白名单。

预期：请求能到达下游；相邻的业务接口不带 token 必须 401。

### T-GW-08 缺失和畸形 Authorization

分别测试：无头、`Basic xxx`、`Bearer` 无值、随机字符串、签名被修改、已过期 token。

预期：均返回 401；请求不进入业务 Controller。

### T-GW-09 按端校验 aud

组合测试：

| token | 请求路径 | 预期 |
|---|---|---|
| appToken | `/app/**` | 放行 |
| appToken | `/driver/**` | 401 |
| appToken | `/admin/**` | 401 |
| driverToken | `/driver/**` | 放行 |
| adminToken | `/admin/**` | 放行 |

同时验证“签名密钥正确但 aud 错误”仍不能跨端访问。

### T-GW-10 防伪造 `X-User-Id`

步骤：携带 `sub=10001` 的 appToken，同时传 `X-User-Id: 99999`，调用会回显或记录当前用户的乘客接口。

预期：

- 网关先删除客户端值，再注入 `10001`。
- BFF 日志/业务结果中不得出现伪造身份 `99999`。

### T-GW-11 Request-Id

分别测试客户端不传和主动传入 Request-Id。

预期：网关生成或透传合法 ID；网关与下游日志可用同一 ID 串联，不能每层无条件覆盖。

## 5. WebSocket 测试

### T-GW-12 司机 WS Upgrade

步骤：

1. 使用 driverToken 调用 `POST /driver/api/v1/auth/ws-token`。
2. 连接 `ws://127.0.0.1:18080/driver/ws/v1/stream?token=<wsToken>`。
3. 发送当前协议支持的 PING。

预期：握手返回 101；收到 PONG/指派列表消息；网关不把 WS 当普通 HTTP 超时关闭。

### T-GW-13 乘客 WS Upgrade

使用 appToken 换小票后连接 `/app/ws/v1/stream?token=<wsToken>`。

预期：有效小票 101；订单变化后可收到 `ORDER_CHANGED`。网关只放行握手，身份最终由 passenger-api 校验。

### T-GW-14 WS 非法小票

测试缺 token、HTTP token 直接当小票、错端小票、过期小票、登出前签发的小票。

预期：握手失败，不建立会话；不能因为 WS 路径在网关白名单就绕过 BFF 鉴权。

## 6. 配置开关回归

### T-GW-15 临时关闭网关鉴权

设置 `GATEWAY_JWT_REQUIRE_AUTH=false`，不带 token 调用业务接口。

预期：网关可放行，但不会凭空注入可信用户 ID；依赖 `X-User-Id` 的 BFF 仍可返回 401。恢复 `true` 后重新验证 T-GW-07～10。

## 7. 回归命令与证据

建议保留：curl/浏览器 Network 结果、网关和 BFF 同一 Request-Id 日志、WS 101 截图。当前 gateway 缺少独立自动化测试，配置或 Filter 变更时本文用例不可省略。

## 8. 2026-07-17 实测结果

- 三端正常 token 经网关访问各自业务接口成功；app token 访问 driver/admin、driver token 访问 app/admin 均不能跨端使用。
- 乘客和司机 WS 均通过网关完成小票握手与消息回归，订单变化能触发端侧 HTTP 对齐。
- gateway、admin-api 以及本次补齐 Actuator 的 passenger、capacity、calculate、wallet 均能返回标准健康响应；其中 passenger/admin-api/capacity/calculate/wallet 实测为 HTTP 200、`{"status":"UP"}`。
- 本次未关闭 `GATEWAY_JWT_REQUIRE_AUTH`，生产启动安全校验仍保持开启口径。
