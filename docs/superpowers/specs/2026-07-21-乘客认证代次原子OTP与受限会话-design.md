# 乘客认证代次、原子 OTP 与受限会话设计

## 1. 决策摘要

本设计是乘客账号生命周期 P2 子设计，承接已合入 `main` 的生命周期数据模型、YAML 计划、运行快照和本地状态机。

已确认结论：

1. `customer.auth_epoch` 是认证代次唯一权威；Redis 不能决定放行。
2. P2 切换时允许全部旧 Token 一次性失效，不回填 `passenger:tv:*`，不兼容 JWT `tv`。
3. 保持单会话：每次成功认证及登出都递增数据库 epoch，新登录踢掉旧登录。
4. 普通与受限会话沿用同一 JWT 密钥和 audience，以 `scope` 区分。
5. 受限会话仅支持 HTTP，不签发 WS Token。
6. 每次鉴权都查询 passenger 数据库状态；Redis 只用于发现更大 epoch 后快速拒绝。
7. LOGIN、PHONE_CHANGE_NEW_PHONE、ACCOUNT_CANCEL OTP 按 Purpose 隔离并以 Lua 原子消费。
8. OTP 已消费而数据库失败时不恢复；用户重新获取验证码。
9. P2 建立换号/注销底层 Application Service，但不挂公开 Controller，不切换旧 settings 流量。
10. epoch 变化后关闭本节点 WS；跨实例可靠关闭留给 P6。

## 2. 范围

### 2.1 本期目标

- JWT 从 `tv` 迁移为数据库权威 `ae`。
- 建立 `NORMAL/LIFECYCLE_RESTRICTED` 会话契约。
- 建立 passenger 内部认证状态查询和内部服务身份校验。
- 统一三类 OTP 的 Key、结果和原子消费。
- 实现未公开的注销建栅栏与换号事务，复用 P1 `LifecycleSnapshotStore`。
- 本节点主动关闭 WS，并以 epoch 拒绝旧 Token 重连。
- 为 P3 动作码门禁提供可信认证上下文。

### 2.2 非目标

- 不实现 order/wallet/calculate 参与者和跨服务清理。
- 不开放新的换号/注销外部接口。
- 不实现完整动作码矩阵；P2 对受限 scope 默认拒绝，P3 再精确开放。
- 不实现跨实例 WS 广播、Kafka 发布器、XXL 恢复和人工后台。
- 不删除旧 settings 编排；正式迁移属于 P7。
- 不增加多设备、refresh token、第二套 JWT 或 opaque token。

## 3. 现状问题

当前 passenger-api 在 Redis `passenger:tv:{customerId}` 递增版本，并把 `tv` 写入 JWT。HTTP Filter 与 WS Handshake 只比较 Redis，导致认证事实无法与 lifecycle MySQL 事务原子提交。换号/注销成功后 BFF 才递增 Redis，存在部分成功窗口；Redis 丢失或陈旧会改变认证结果。

登录、换号、注销均采用 `GET -> 比较 -> DELETE` 验证 OTP，并发请求可能重复使用。JWT 也没有 lifecycle scope，已建立 WS 不会随账号状态变化主动关闭。

## 4. 组件边界

### 4.1 passenger

- 权威读写 `auth_epoch/lifecycle_status/current_lifecycle_operation_no`。
- 生成并原子消费三类 OTP。
- 在成功认证、登出、换号和注销事务内递增 epoch。
- 实现未公开的注销建栅栏和换号 Application Service。
- 返回 JWT 签发材料，但不持有 JWT 密钥。

### 4.2 passenger-api

- 根据 passenger 返回结果签发 JWT。
- 解析 `ae/scope/audit/operationNo`。
- 每次 HTTP/WS 认证调用 passenger 查询数据库状态。
- 拒绝受限 Token 换 WS Token。
- epoch 变化后关闭本节点已有 WS。

passenger-api 不再递增认证版本。

### 4.3 gateway

gateway 继续校验签名、audience、有效期并注入 `X-User-Id`。数据库 epoch 与 scope 的最终裁决仍在 passenger-api，P2 不要求 gateway 调用 passenger。

## 5. JWT 契约

Claims：

```text
sub          customerId
phone        当前手机号；仅兼容现有 BFF 使用
ae           customer.auth_epoch
scope        NORMAL / LIFECYCLE_RESTRICTED
audit        1=HTTP / 2=WebSocket
operationNo  仅受限会话必填
aud          app-bff
iat/exp      签发与到期时间
```

P2 不生成或接受 `tv`。缺少 `ae/scope` 的旧 Token 返回 401。

普通 HTTP Token 为 `NORMAL + audit=1`；普通 WS Token 为 `NORMAL + audit=2`；受限 Token 为 `LIFECYCLE_RESTRICTED + audit=1 + operationNo`，默认 TTL 1800 秒。

始终拒绝：

- `LIFECYCLE_RESTRICTED + audit=2`。
- NORMAL 但 customer 不为 ACTIVE。
- LIFECYCLE_RESTRICTED 但 customer 不为 CANCELLING。
- 受限 operationNo 与当前 Operation 不一致。

## 6. 数据库强校验

passenger 内部认证状态返回：

```text
customerId
businessStatus
lifecycleStatus
authEpoch
currentLifecycleOperationNo
allowedScope
```

规则：ACTIVE 允许 NORMAL；CANCELLING 且存在当前 Operation 时允许 LIFECYCLE_RESTRICTED；CANCELLED、已删除或不存在时拒绝。

Filter 顺序：

1. 校验 JWT 签名、aud、exp 和 claim 类型。
2. 校验 audit 与通道。
3. Redis epoch 大于 Token ae 时可立即拒绝。
4. 调 passenger 内部接口读取数据库。
5. 比较 customerId、authEpoch、scope、lifecycleStatus 和 operationNo。
6. 全部一致才注入可信上下文。

Redis 相等不能省略数据库查询。Redis 不可用时继续查数据库；passenger 或数据库不可用时返回 503，失败关闭。

## 7. 登录、登出与受限恢复

### 7.1 ACTIVE 登录

密码或 LOGIN OTP 成功后，passenger 在事务中按 `id + is_deleted=0 + lifecycle_status=ACTIVE` 原子执行 `auth_epoch=auth_epoch+1`，返回新 epoch 与 NORMAL。并发登录得到不同 epoch，只有最后一次 Token 有效。

### 7.2 CANCELLING 重新认证

用户可用密码或 LOGIN OTP 重新证明身份，但不能获得普通会话。passenger 在事务中递增 customer epoch，并把当前 Operation `restricted_auth_epoch` 更新为新值，返回 LIFECYCLE_RESTRICTED 与 operationNo。passenger-api 签发 30 分钟受限 HTTP Token。

### 7.3 CANCELLED 登录

旧 customer 不允许认证。释放后的手机号若重新注册，必须对应新的 customer.id。

### 7.4 登出

顺序固定为：

1. passenger 数据库递增 epoch并提交。
2. passenger-api 关闭本节点 WS。
3. 执行现有退出时订单处理。

第 3 步失败不能恢复 epoch；响应应明确“已经登出，订单处理需重试或查询”。

## 8. 原子 OTP

Purpose：

```text
LOGIN
PHONE_CHANGE_NEW_PHONE
ACCOUNT_CANCEL
```

Key：

```text
app:otp:v2:LOGIN:{phone}
app:otp:v2:PHONE_CHANGE_NEW_PHONE:{customerId}:{newPhone}:{lifecycleVersion}
app:otp:v2:ACCOUNT_CANCEL:{customerId}:{lifecycleVersion}
```

换号与注销绑定 lifecycleVersion，账号版本变化后旧验证码失效。

单 Key Lua 返回：

```text
MISSING   key 不存在
MISMATCH  值不匹配，不删除
CONSUMED  值匹配并删除
```

不允许 Java 保留 OTP `GET -> DELETE` 成功路径。OTP CONSUMED 后 MySQL 失败不恢复；相同幂等请求若已在数据库成功，直接返回既有 Operation，不再消费 OTP；同幂等键改内容返回 409。发送间隔和每日次数沿用现有规则。

旧 settings 入口只迁移到原子 OTP 组件，业务编排暂不切换。

## 9. 注销建栅栏事务

P2 只实现内部 Application Service：

1. 按 customerId/type/idempotencyKey 查询既有 Operation。
2. 同 requestHash 返回既有结果；不同 requestHash 返回 409。
3. 原子消费绑定 expectedLifecycleVersion 的 ACCOUNT_CANCEL OTP。
4. 开启 passenger MySQL 事务。
5. CAS customer：ACTIVE、版本一致、未删除、没有活动 Operation。
6. lifecycle_status -> CANCELLING，lifecycle_version+1，auth_epoch+1。
7. 设置 current_lifecycle_operation_no。
8. 用 P1 SnapshotFactory/Store 写 Operation/Step/Event/Outbox。
9. Operation REQUESTED -> FENCED，写 restricted_auth_epoch。
10. 提交后返回 operationNo、FENCED、restrictedAuthEpoch。

customer、Operation、Step、Event、Outbox 任一失败时 MySQL 整体回滚，OTP 不恢复。

## 10. 换号事务

P2 只实现内部 Application Service：

1. 检查幂等 Operation并原子消费 PHONE_CHANGE_NEW_PHONE OTP。
2. CAS customer：ACTIVE、版本一致、未删除、没有活动 Operation。
3. 校验新旧手机号不同，并依赖 `phone_active` 唯一索引处理并发占用。
4. 更新同一条 customer.phone。
5. 旧绑定 ACTIVE -> REPLACED，新增下一 bindingVersion 的 ACTIVE 记录。
6. lifecycle_version+1、auth_epoch+1。
7. 创建并完成 PHONE_CHANGE Operation/Step/Event/Outbox。
8. 返回新 epoch；passenger-api 关闭本节点 WS并要求重新登录。

customer.id 不变，不迁移订单、钱包、券或积分。

## 11. WS 撤销

passenger-api 增加：

```text
closeCustomerSessions(long customerId, String reason)
```

登出、换号、注销建栅栏和注销完成时调用。关闭原因不得包含手机号或 Token。旧 WS Token 重连因数据库 epoch 不匹配而拒绝。P2 只保证本节点；跨实例广播和重试属于 P6。

## 12. 内部安全

- passenger-api 使用独立内部服务 Token 调 passenger。
- 密钥仅从环境变量读取，不进入 Git。
- 非 local/dev/test 缺失或使用开发默认值时启动失败。
- 浏览器不得访问 internal 路径。
- 记录 requestId、服务身份和结果码，不记录 Token、OTP 或手机号原文。

P2 不引入 mTLS 基础设施，内部 Token 是当前最小可落地方案。

## 13. 错误语义

- passenger/数据库不可用：503并失败关闭。
- Redis 不可用：跳过快速拒绝，继续数据库强校验。
- JWT 缺 ae/scope 或 claim 类型错误：401。
- epoch/operationNo 不匹配：401。
- scope 不允许当前通道或接口：403。
- OTP MISSING/MISMATCH：统一 401，不泄露细节。
- lifecycleVersion/CAS 冲突、新手机号唯一冲突：409。
- 内部服务身份错误：401/403并写安全日志。
- OTP 已消费但事务失败：返回业务错误并要求重新获取验证码。

## 14. 测试要求

- 并发消费同一 OTP 仅一个 CONSUMED，三种 Purpose 不能交叉消费。
- lifecycleVersion 变化后旧换号/注销 OTP 无效。
- ACTIVE 登录递增 epoch并签发 NORMAL，新登录使旧 HTTP/WS Token 失效。
- CANCELLING 重新认证签发受限 Token并更新 restricted_auth_epoch。
- CANCELLED 拒绝；旧 tv、缺 scope Token 均拒绝。
- Redis 陈旧、缺失或不可用时仍由数据库正确裁决。
- passenger 不可用时失败关闭。
- 受限 operationNo 不匹配拒绝；受限 Token 不能换 WS Token。
- 注销建栅栏任一步失败时五类数据库状态整体回滚。
- 同幂等请求重放、同键改内容冲突。
- 换号与注销并发只有一方通过 lifecycleVersion CAS。
- 换号后 customer.id 不变、绑定历史正确、旧 Token 失效。
- 本节点 WS 主动关闭，旧 WS Token 重连拒绝。
- 登出后的订单处理失败不恢复 epoch。

## 15. 可观测性

记录 auth state DB 查询 QPS/P95/P99/错误率；JWT 拒绝原因；按 Purpose 聚合的 OTP 结果；epoch bump/CAS 冲突；restricted 签发；本节点 WS close；内部接口 401/403/5xx。指标与日志不得包含 OTP、Token、手机号原文或完整 OTP Key。

## 16. 上线与回滚

上线顺序：

1. 部署 passenger 的 epoch、原子 OTP、认证状态和未公开 lifecycle Service。
2. 部署 passenger-api 的 ae/scope JWT、数据库强校验和本节点 WS 撤销。
3. 在统一切换点停止接受 tv Token，强制全部乘客重新登录。
4. 不读取、不扫描、不回填 `passenger:tv:*`。
5. 观察认证失败率、内部查询延迟、DB/Redis错误、受限签发和 WS 重连拒绝。
6. 稳定后删除 PassengerTokenVersionStore 写路径，旧 Key 延迟清理。

回滚不能恢复旧 Token、降低数据库 epoch或把 Redis tv 重新提升为权威。已进入 CANCELLING 的 Operation 继续前向恢复。

## 17. P2 验收标准

- 新 JWT 全部使用 ae+scope，不再依赖 tv。
- 新登录/登出保持单会话，数据库 epoch 唯一权威。
- Redis 陈旧、丢失或不可用不会错误放行旧 Token。
- 三种 OTP Purpose 隔离且只能原子消费一次。
- 受限 Token 只支持 HTTP并绑定当前 operationNo。
- CANCELLING 用户可重新认证获得新受限 Token。
- 注销建栅栏与换号事务复用 P1 SnapshotStore。
- epoch 变化后本节点 WS 关闭，旧 WS Token 不能重连。
- 没有新公开换号/注销 Controller，旧 settings 未切到新 Saga。
- passenger 与 passenger-api 全量 verify 通过。

## 18. P2 实现结果与上线检查单

### 18.1 已落地组件

认证职责已经收束到以下稳定边界：

- passenger 的 `AtomicOtpService` 统一生成并以 Lua 原子消费 LOGIN、PHONE_CHANGE_NEW_PHONE、ACCOUNT_CANCEL 三种 OTP。
- passenger 的 `PassengerAuthEpochService` 统一完成登录/重新认证的 epoch 递增、权威状态读取和登出 CAS。
- passenger 的 `PassengerInternalAuthFilter` 保护内部路径；`PassengerInternalAuthController` 只适配
  `GET /api/v1/internal/auth-state/{customerId}` 与 `POST /api/v1/internal/auth-state/logout`。
- passenger 的 `AccountCancellationFenceService` 与 `CustomerPhoneChangeService` 是未公开的生命周期应用服务，
  共同复用 P1 快照和状态机；没有新增 lifecycle Controller。
- passenger-api 的 `AppJwtService` 只签发、解析 `ae/scope/audit` 契约；`PassengerJwtAuthFilter` 与
  `PassengerWsHandshakeInterceptor` 每次均回查 passenger 权威状态，认证裁决不依赖 Redis。
- passenger-api 的 `PassengerWsSessionRegistry` 负责本节点 generation 栅栏、单会话替换和 epoch 变化后的连接关闭。
- 两模块使用 `docs/superpowers/contracts/passenger-auth-state-v1.json` 作为同一份认证状态契约测试源。

配置口径如下：

- 两端内部身份统一读取 `PASSENGER_INTERNAL_TOKEN`；非 local/dev/test 环境必须至少 32 bytes，且不得包含
  `change-me` 或以 `dev-passenger-` 开头。
- passenger-api JWT audience 固定为 `app-bff`，密钥读取 `JWT_SECRET_APP`；普通与受限 TTL 分别读取
  `JWT_EXPIRATION_SECONDS_APP`（默认 86400 秒）和 `JWT_RESTRICTED_EXPIRATION_SECONDS_APP`（默认 1800 秒）。
- 旧 settings 的公开入口继续保留原即时编排，不创建 Saga；其换号/注销 SQL 已在同一数据库写入中递增或终结
  `auth_epoch`，因此不会重新引入 Redis 会话版本权威。

### 18.2 固定低基数指标

以下指标只允许枚举白名单 Tag，不包含 customerId、operationNo、手机号、Token、OTP Key 或异常 message：

- `passenger.auth.state.query{result}`：passenger 权威状态查询时延和结果。
- `passenger.auth.jwt.rejected{reason}`：JWT/认证状态拒绝原因。
- `passenger.auth.otp.consume{purpose,result}`：按用途聚合的 OTP 消费结果。
- `passenger.auth.epoch.bump{cause,result}`：认证代次变更结果。
- `passenger.auth.restricted.issued`：受限会话签发次数。
- `passenger.auth.ws.closed{reason}`：本节点 WS 关闭次数。
- `passenger.lifecycle.cas.conflict{operationType}`：换号/注销生命周期 CAS 冲突。

### 18.3 上线检查单

1. 先部署 passenger，确认 schema 已包含 `auth_epoch/lifecycle_status/current_lifecycle_operation_no`，内部认证状态接口正常，
   且生产 `PASSENGER_INTERNAL_TOKEN` 通过启动校验。
2. 再部署 passenger-api，确认 `aud=app-bff`、JWT 密钥和相同的内部 Token 已注入。
3. 在统一切换点停止接受 `tv`，不读取、不扫描、不回填旧 Redis 会话版本键，并强制所有乘客重新登录。
4. 观察 HTTP/WS 的 401、403、503 比例，`passenger.auth.state.query` 的 P95/P99/错误率，受限会话签发量、
   epoch/CAS 冲突和 WS 关闭量；异常上升时先确认 passenger 与数据库容量和内部链路。
5. 验证 ACTIVE 普通登录、CANCELLING 受限重认证、旧 HTTP/WS Token 拒绝、登出 CAS 和本节点 WS 撤销。
6. 确认旧 settings 仍按原入口工作，且没有新公开换号/注销 Controller。

### 18.4 回滚边界

回滚只能回退无状态的 BFF 发布物或停止新增流量，不能降低任何 customer 的 `auth_epoch`，不能恢复已经失效的旧
Token，不能重新接受 `tv`，也不能把 Redis 会话版本恢复为认证权威。已经进入 CANCELLING 或已经完成换号的事务继续
以前向恢复和数据库状态为准；若 passenger/数据库不可用，HTTP 与 WS 必须保持 503 失败关闭，禁止绕过权威校验放行。
