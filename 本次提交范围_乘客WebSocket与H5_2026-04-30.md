# 本次提交范围说明（乘客 WebSocket + 网关 + H5 / 2026-04-30）

> 便于 **拆 PR / 写 commit message**。若工作区里还有 **order / capacity / driver-api 其他改动**，请与本专题 **分开提交**，避免混杂。

---

## 一、后端仓库 `didi-taxi`（建议单独一个 commit 或「乘客 WS + 网关」+「文档」）

### 1.1 与本专题强相关（建议纳入同一功能提交）

| 模块 | 路径/说明 |
|------|-----------|
| **gateway** | `gateway/src/main/java/com/sx/gateway/filter/JwtAuthenticationGlobalFilter.java` — `isPublic` 增加 **`GET /app/ws/**`** 与 **`/driver/ws/**` 对称，避免 `require-auth` 下握手 **401** |
| **passenger-api** | `pom.xml`：`spring-boot-starter-websocket` |
| | `PassengerApiSpringApplication.java`：`@EnableScheduling` |
| | `auth/`：`AppJwtService`（`audit` claim）、`ParsedPassengerJwt`、`PassengerJwtAuthFilter`（仅 `audit=1` 走 REST） |
| | `controller/PassengerAuthController.java`：`POST /app/api/v1/auth/ws-token` |
| | `service/PassengerAuthService.java`、`PassengerOrderService.java`（下单/两段式/取消后 `notifyOrderChanged`） |
| | `ws/`：`PassengerWebSocketConfig`、`PassengerWsHandshakeInterceptor`、`PassengerWsSessionRegistry`、`PassengerNoticeWebSocketHandler`、`PassengerWsNotifyService`、`PassengerWsProperties` |
| | `resources/application.yml`：`passenger.ws.*` |

### 1.2 文档（可与 1.1 同 commit 或单独 `docs:` commit）

- `乘客端与司机端_WebSocket_对比.md`（含 **§0.4** 网关白名单、**§13** 网关锚点）
- `乘客司机端_最小闭环接口调用文档.md`（**§1.2a**、修订记录）
- `网关服务_技术.md`（**§3.1** JWT 白名单、修订记录）
- `TODO与差距总览.md`（乘客 WS 条目、修订记录）

### 1.3 工作区中可能存在的「非本专题」改动（请勿与上表混提交）

当前 `git status` 中若仍有 **`capacity/`、`order/`、`driver-api/` 其他文件** 等，属并列需求时请 **另开 commit/PR**。

---

## 二、前端仓库 `didi-taxi-front`（建议一个 commit）

| 子项目 | 文件 | 说明 |
|--------|------|------|
| **didi-passenger-h5** | `src/App.vue` | `ws-token` + WebSocket + 轮询兜底 + **连接世代**防误重连刷小票 |
| | `src/utils/passengerOrderWs.js` | **新增**：`ws`/`wss` origin、`stream?token=` URL、解析 `ORDER_CHANGED` |
| **didi-driver-h5** | `src/App.vue` | 接单后 **「行程」面板** 上移至 **「更多功能入口」** 之上 |
| | `.env.example` | 若有与本功能相关的环境变量说明（以实际 diff 为准） |

---

## 三、建议的 commit message 示例

**后端**

```text
feat(passenger-api): WebSocket 通知通道 + audit=2 ws-token

- JWT 增加 audit；REST 仅 audit=1；握手 /app/ws/v1/stream + Query token
- 单机 ORDER_CHANGED 推送；下单/取消等路径 notify；passenger.ws.enabled 总闸
- fix(gateway): isPublic 放行 GET /app/ws/** 握手，避免 JWT 401 导致狂刷 ws-token

docs: 乘客 WS 对比稿、最小闭环 §1.2a、网关 §3.1、TODO
```

**前端**

```text
feat(passenger-h5): 跟单 WebSocket + 轮询降级 + 重连世代

fix: 主动关旧连时避免 close 回调重复调度 open 导致频繁 ws-token

chore(driver-h5): 行程面板上移至更多功能入口之上
```

---

## 四、验收清单（提交前自测）

- [ ] 网关 **`GATEWAY_JWT_REQUIRE_AUTH=true`** 时，乘客 H5 经网关可建 **WS**，Network **WS** 类型可见 **101**
- [ ] 跟单时 UI：**WebSocket 已连** + 轮询约 **10s** 兜底；断网恢复后行为正常
- [ ] `ws-token` 非周期性狂刷（除真实重连）
- [ ] 司机端首页：**行程** 在 **更多功能入口** 上方（有进行中单时）
