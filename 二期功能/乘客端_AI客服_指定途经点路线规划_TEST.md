# 乘客端 AI 客服：路线核查与指定途经点规划 TEST

> 2026-09-20。本文记录当前已交付范围的验收口径和自动化执行结果；未实现能力与真实运行环境验收单独列出，不能把自动化通过等同于生产验收。

## 1. 当前验收范围

- 已实现：会话创建、按已知会话编号读取历史、文字 SSE、地点候选选择、当前路线核查含义选择、默认路线、指定地点路线、历史路线卡重放、缓存失效追问、消息幂等与失联请求收束。
- 已实现前端：乘客 H5 真实会话、历史消息、文字发送、地点选择、核查含义选择、路线卡片和过期摘要展示。
- 当前未实现：开放式收费站路线方案与 `routeOptionId` 选择、单端点短时续接、会话列表、请求状态、删除会话。
- 不在本期：地图页路线导入客服、路线下单、司机导航、实际行驶核验、停车计费和费用透明问答。

## 2. 核心业务验收

- 新会话不继承地图页起终点或路线；乘客在客服中重新提供完整两端。
- 两端地图身份唯一时，本轮直接继续原始意图；有歧义时返回 `place.choices`，最后一个地点选定后直接继续，不追加“对吗”确认。
- 普通起终点规划返回真实默认 `route.card`；指定一个明确地点且导航证据验证通过时直接返回新路线卡，不要求 `routeCandidateId`。
- “这条路线”只指本会话最新已持久化且 map-service 快照仍有效的路线；核查不得重新规划另一条路线冒充当前路线。
- 收费站、服务区和加油站的进入、出口、入口或到达结论必须来自同一路线的导航动作和有序状态；只有折线接近不能证明通过。
- 地点身份明确但核查含义不明确时返回 `route-check.choices`；选择后继续核查同一 `routeRef` 和地点，不重新解析或换路线。
- 缓存或路线快照过期时返回可恢复追问并持久化，要求乘客重新提供完整起终点；不从历史文字、旧坐标或旧 `routeRef` 恢复地图事实。
- 历史路线卡过期后只展示文字、地点、里程和预计时间摘要，不恢复折线或导航步骤。

## 3. 并发、幂等与失败验收

- begin 在会话行锁内核对幂等键和活动请求；同键并发只允许一个请求执行。
- complete 或 fail 重放数据库真实终态；已完成消息不能被迟到失败覆盖，已失败消息不能被迟到成功覆盖。
- 超过活动请求阈值的失联轮次由后续 begin、启动对账或每 30 秒定时对账收束为 `AI_REQUEST_EXPIRED`。
- passenger-api 使用有界轮次线程池；拒绝时发送 `AI_SERVER_BUSY`。SSE 上限 90 秒，每 15 秒发送 ping，超时取消对应 worker 并尝试发送 `AI_STREAM_TIMEOUT`。
- 模型提取使用独立有界执行器；超时取消 Future，队列满返回 `AI_PROVIDER_BUSY`，空结果返回 `AI_PROVIDER_INVALID_RESPONSE`。
- map-service 请求体错误返回真实 HTTP 400 和 `AI_INVALID_REQUEST`；未分类异常返回真实 HTTP 500 和 `AI_INTERNAL_ERROR`。
- passenger-api 与 map-service 的内部 token 必须一致；非宽松环境启动时校验至少 32 字节且不是开发占位值，鉴权失败返回 `AI_INTERNAL_AUTH_FAILED`。

## 4. 自动化执行记录

- `mvn -pl map,passenger,passenger-api clean test`：分模块执行并通过。
- passenger：221 个测试，0 失败、0 错误、0 跳过。
- map：129 个测试，0 失败、0 错误，其中 1 个真实 DeepSeek 手动接入测试因未显式启用和未配置外部密钥而跳过。
- passenger-api：229 个测试，0 失败、0 错误、0 跳过。
- 三个后端模块合计发现 579 个测试，实际执行 578 个，失败和错误均为 0。
- `npm test`：通过；39 个前端调用与后端 Controller 路由匹配。
- `npm run build`：通过；乘客 H5 Vite 生产构建完成。
- `git diff --check`：通过。

## 5. 运行环境与人工验收

- 在 Nacos 的 `map-service-local.yml` 和 `passenger-api-local.yml` 注入同一个 `MAP_INTERNAL_TOKEN`，验证漏配、占位值和两端不一致均能快速失败或返回稳定错误码。
- 核验 Redis 连接、5 分钟 TTL、缓存丢失、条件版本变化以及服务重启后的追问行为。
- 分别配置 DashScope、DeepSeek 和高德密钥；验证模型超时、限流、空响应、地图超时、无可达路线和真实 POI 歧义。
- 使用真实收费站、服务区和加油站样本核验导航到达事件、入口／出口角色、已在高速主线的语义冲突及证据不足结果。
- 通过 Gateway 验证 SSE 心跳、90 秒边界、客户端主动断线、反向代理空闲超时以及刷新页面后的历史恢复。
- 核对实际 Passenger 数据库：新环境使用 `passenger_schema.sql`；存量环境按顺序执行并记录 `passenger_ai_conversation_patch.sql` 和必要的 `passenger_ai_message_drop_customer_id_patch.sql`，不能只依赖 `CREATE TABLE IF NOT EXISTS` 升级旧表。

## 6. 未实现能力的验收门禁

- BUG-06 的会话列表、请求状态、删除会话没有 Controller 映射；实现前不得将其计入断线恢复或会话管理验收。
- `PassengerRouteOptionCache` 未装配；不得展示或接受 `route.options`、`routeOptionId`。
- `PassengerRoutePartialEndpointCache` 未装配；缺少任一端时应追问完整起终点，不宣称可以只缓存一端后续接。
- 上述能力后续实现时，必须同步 PRD、TECH、API、本 TEST、前后端契约检查和相应自动化，单独记录真实运行环境验收结果。
