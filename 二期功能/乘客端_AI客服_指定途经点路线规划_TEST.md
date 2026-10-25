# 乘客端 AI 客服：路线核查与指定途经点规划 TEST

> 2026-10-25。AI 客服第一期功能已完成代码开发。本文记录第一期验收口径和已有自动化结果；运行环境与端到端验收尚未全部完成，不能把开发完成或自动化通过等同于生产上线验收。

## 1. 当前验收范围

- 已实现：会话创建／列表／历史／请求状态／逻辑删除、文字 SSE、单端点短时续接、地点候选选择、当前路线核查含义选择、默认路线、指定地点路线、历史路线卡重放、缓存失效追问、消息幂等与失联请求收束。
- 已实现前端：乘客 H5 会话列表、切换、删除、断流状态对账、历史消息、文字发送、地点选择、核查含义选择、路线卡片和过期摘要展示。
- 不纳入第一期、暂不开发：开放式收费站路线方案与 `routeOptionId` 选择；相关目标契约不构成第一期验收失败。
- 不在本期：地图页路线导入客服、路线下单、司机导航、实际行驶核验、停车计费和费用透明问答。

## 2. 核心业务验收

- 新会话不继承地图页起终点或路线；乘客可一轮提供完整两端，也可先提供一个经地图确认的端点、下一轮补齐另一端。
- 单端点只在 map-service 保存真实 Place，并绑定乘客、会话、服务端追问 requestNo 和条件版本；缓存失效、跨会话或旧版本续接都必须重新询问，不从聊天摘要恢复坐标。
- 两端地图身份唯一时，本轮直接继续原始意图；有歧义时返回 `place.choices`，最后一个地点选定后直接继续，不追加“对吗”确认。
- 起点候选不含明确的高速出口，终点候选不含明确的高速入口；同名反向地点不静默替换，普通景区入口不误过滤。单端点续接和旧缓存选择同样不能接受反向候选。收费站端点默认路线与指定地点路线出卡前，有匹配站点到达事件时核对前后道路状态；没有到达事件时只允许端点附近、路线首段／末段 1 公里内的正向道路变化或明确导航动作。明确选中的收费站入口可接受从该 POI 起步、首段道路为带编号高速入口匝道且导航指令明确接入同编号高速的合并步骤；同处高速主线、未写明接入高速的匝道均不得由此出卡。远处收费站或仅靠折线接近不得肯定出卡；无法证明时不生成成功卡片。
- 宽泛“高速路口”起点候选不混入服务区交叉口、地铁站；宽泛“西溪湿地”终点候选不混入地铁站、售票处和内部水塘。重复 POI 合并，景区主体优先展示，宽泛查询最多 5 项；明确点名售票处时仍可搜索。原始搜索页达到 10 条时不因筛选后候选变少而自动确认。
- 普通起终点规划返回真实默认 `route.card`；在已有默认路线后说“改走 X”时，以该路线的可信起终点生成新路线；首轮同时给出 A、B、X 时也直接进入指定地点规划。指定地点有歧义先返回 `place.choices`，选择后继续同一途经要求；导航证据验证通过时直接返回新路线卡，不要求 `routeCandidateId`。
- “这条路线”只指本会话最新已持久化且 map-service 快照仍有效的路线；核查不得重新规划另一条路线冒充当前路线。
- “有没有经过 X 的路线”在本轮未重述两端时沿用本会话最新有效路线的可信起终点，只回答存在性结论、不展示新路线卡；快照过期或没有历史路线时追问完整两端，保留地点与查询意图，下一轮补齐端点后继续同一查询；本轮只给一端时不得拼接旧路线另一端。
- 收费站、服务区和加油站的进入、出口、入口或到达结论必须来自同一路线的导航动作和有序状态；只有折线接近或“到达收费站”事件不能证明驶入／驶出。收费站前由普通道路变为高速才证明驶入，由高速变为普通道路才证明驶出，方向相反必须返回语义冲突。
- 地点身份明确但核查含义不明确时返回 `route-check.choices`；选择后继续核查同一 `routeRef` 和地点，不重新解析或换路线。
- 缓存或路线快照过期时返回可恢复追问并持久化，要求乘客重新提供完整起终点；不从历史文字、旧坐标或旧 `routeRef` 恢复地图事实。
- 历史路线卡过期后只展示文字、地点、里程和预计时间摘要，不恢复折线或导航步骤。
- 地点选择即使客户端篡改 `choiceName`／`choiceCity`，落库乘客正文和 `turn.started` 仍采用本会话已持久化选项摘要中的名称与城市；任务、版本、编号不匹配时不创建错误的乘客轮次。同键重放保持相同的规范正文。

## 3. 并发、幂等与失败验收

- begin 在会话行锁内核对幂等键和活动请求；同键并发只允许一个请求执行。
- complete 或 fail 重放数据库真实终态；已完成消息不能被迟到失败覆盖，已失败消息不能被迟到成功覆盖。
- 超过活动请求阈值的失联轮次由后续 begin、启动对账或每 30 秒定时对账收束为 `AI_REQUEST_EXPIRED`；默认和有效下限均为 120 秒，即使旧配置仍填 60 秒，开始后约 70 秒的有效轮次也不得被接管或定时对账收束。PRD 的 60 秒单轮执行截止尚未严格实现，不得把 120 秒失联阈值误当执行预算。
- passenger-api 使用有界轮次线程池；拒绝时发送 `AI_SERVER_BUSY`。SSE 上限 90 秒，每 15 秒发送 ping，超时取消对应 worker 并尝试发送 `AI_STREAM_TIMEOUT`。
- 模型提取使用独立有界执行器；超时取消 Future，队列满返回 `AI_PROVIDER_BUSY`，空结果返回 `AI_PROVIDER_INVALID_RESPONSE`。
- map-service 请求体错误返回真实 HTTP 400 和 `AI_INVALID_REQUEST`；未分类异常返回真实 HTTP 500 和 `AI_INTERNAL_ERROR`。
- passenger-api 与 map-service 的内部 token 必须一致；非宽松环境启动时校验至少 32 字节且不是开发占位值，鉴权失败返回 `AI_INTERNAL_AUTH_FAILED`。
- 页面刷新后仅当最新历史页末尾是已落库、尚无客服回复的乘客请求时自动查询该请求状态；恢复期间同会话不可继续发送，终态回复合并且不重复发请求；切换会话或离开页面后不再由旧轮次更新当前页面。未落库的前端请求不能凭空恢复。

## 4. 自动化执行记录

- 上一轮全量基线：passenger 221 个、map 发现 129 个（1 个外部模型手动测试跳过）、passenger-api 229 个；合计发现 579 个、实际执行 578 个，失败和错误均为 0。该结果早于本轮四项能力改动，只作为基线。
- 本轮 map 聚焦验证：`AiRouteInternalControllerTest` 14、`PassengerRouteEndpointPreparationServiceTest` 9、`PassengerRouteExtractionServiceTest` 17，共 40 个测试通过。
- 本轮 passenger-api 聚焦验证：`AiRouteTurnServiceTest` 28、`PassengerAiConversationControllerTest` 8、`PassengerActionResolverTest` 46，共 82 个测试通过。
- 本轮 passenger 会话集成场景补入既有 `AiConversationServiceIntegrationTest`；清理 `target` 中残留的重复 class 后，26 个测试全部通过，覆盖会话列表标题回落、请求状态、幂等删除和删除后迟到完成拒绝。三个模块的主代码与测试代码均已编译通过。
- 收费站方向误判回归补入既有 `CurrentRoutePassageServiceTest`：覆盖“实际从五常收费站驶出，不得回答从同名入口驶入”以及“高速到普通道路可证明从匹配出口驶出”。相关 map 聚焦 18 个测试通过；随后执行 `mvn -pl map test`，发现 136 个、实际执行 135 个，失败和错误均为 0，1 个需要外部模型令牌的手动测试按设计跳过。
- BFF 回答文案回归补入既有 `AiRouteTurnServiceTest`：确认 `TOLL_ACTION_DIRECTION_CONFLICT` 会明确回答“属于驶出高速，不是从该入口驶入”，不会再生成肯定的入口结论；该测试类 34 个测试全部通过。
- `npm test`：通过；39 个前端调用与后端 Controller 路由匹配。
- `npm run build`：通过；乘客 H5 Vite 生产构建完成。
- `git diff --check`：通过。
- 2026-09-21 收费站端点方向回归：在既有 `PassengerRouteEndpointResolverTest`、`PassengerRouteEndpointPreparationServiceTest` 中补充起点出口／终点入口过滤、普通景区入口保留、旧选项拒绝、收费站导航事件及有序道路状态、默认路线证据不足不缓存的场景。两类定向测试共 20 个通过；相邻 `AiRouteInternalControllerTest`、`RouteCardAssemblerTest`、`WaypointRoutePlanningServiceTest`、`CurrentRoutePassageServiceTest` 共 35 个通过，合计 55 个。尝试 `mvn -pl map test -q` 时 Spring 测试上下文持续阻塞在本机类文件磁盘读取，已中止该次测试；本轮不得宣称 map 全量回归通过。
- 本轮续接修正：此前把途经收费站的“到达事件必需”规则套在路线起点，真实选择“莫干山收费站入口(湖州方向)”到“西溪国家湿地公园洪园”时触发 `AI_ROUTE_UNVERIFIABLE`。现改为端点专用的首段／末段方向证据；passenger-api 对该码返回明确的证据不足说明。需在服务更新后复验真实 H5 样本；阿里模型偶发读取超时与该方向修复是两个独立问题。
- 本轮定向回归：`PassengerRouteEndpointResolverTest` 13、`PassengerRouteEndpointPreparationServiceTest` 11、`AiRouteInternalControllerTest` 14、`WaypointRoutePlanningServiceTest` 8，map 共 46 个通过；`AiRouteTurnServiceTest` 35 个通过，合计 81 个，失败／错误均为 0。覆盖无收费站到达事件但起点驶入／终点驶出方向明确、远处入口及绕回同站不误认、已在高速上不冒充新驶入、证据不足错误码转可读 `turn.failed` 并持久化。`git diff --check` 通过；未启动、停止或重启后端服务，真实页面复验仍待用户运行的服务更新后进行。
- 2026-09-21 起终点候选收紧：在既有 `PassengerRouteEndpointResolverTest` 中增加高速口候选去杂／去重／本地优先及宽泛景区候选精简、精确点名仍可查场景；`PassengerRouteEndpointResolverTest` 15 个、`PassengerRouteEndpointPreparationServiceTest` 11 个，map 定向共 26 个通过。此验证仅覆盖代码规则，真实高德搜索结果与页面交互仍需在用户更新服务后复验。
- 未新建测试类，新增场景均补入既有测试类。此前完成过 map 模块全量回归，但在最近一次入口匝道合并步骤修复后仅重新执行 `PassengerRouteEndpointResolverTest`、`PassengerRouteEndpointPreparationServiceTest` 聚焦测试并通过；该修改后的 map 全量，以及 passenger 与 passenger-api 全量回归仍待执行，不能沿用此前结果充当最终验收。
- 2026-09-22 存在性查询与失联阈值回归：`AiRouteTurnServiceTest` 39 个、`AiConversationServiceIntegrationTest` 27 个，均 0 失败／错误／跳过。覆盖本轮缺少两端时复用本会话历史路线、历史路线过期、没有历史路线、只给一端不拼接旧路线，以及旧 60 秒配置下约 70 秒的有效轮次不被 begin 或后台对账提前收束。仅完成定向自动化；运行中的 passenger-api 与 passenger 服务未由本次测试重启，真实页面复验仍待服务更新。
- 2026-09-22 两项 P2 修复定向验证：`AiRouteTurnServiceTest` 41 个、`PassengerAiConversationControllerTest` 8 个通过（含伪造展示名称、跨页旧请求同键重放和未知选项不落库）；乘客 H5 `npm test` 中 39 条 API 契约匹配、恢复纯逻辑测试 2 个通过，`npm run build` 通过。自动化不能单独替代真实页面联调，见下一条。
- 2026-09-22 本地页面联调：网关 `127.0.0.1:18080/actuator/health` 返回 200；已登录乘客 H5 从“德清”与“西溪湿地”的起终点歧义候选逐步选择“德清县（湖州市）”和“西溪国家湿地公园（杭州市）”，刷新后聊天正文与所选候选一致，最终展示 49.9 公里、约 51 分钟的路线卡。发送“莫干山收费站入口（湖州方向）到西溪国家湿地公园洪园”后在处理中立即刷新；重进会话可见“仍在处理中，页面会自动更新”、输入禁用，随后无需再刷新自动出现地点候选，同轮乘客消息未重复。伪造客户端名称仅由自动化覆盖，未在真实页面篡改请求。另有“这条路线经过五常收费站吗？”返回“服务暂时不可用，请稍后重试”；运行日志定位为 map-service 调用 DashScope 起终点提取时读超时，返回 `AI_PROVIDER_UNAVAILABLE`（503），不应记为当前路线核查通过。
- 2026-10-25 提交前修复与回归：存在性查询复用过期路线时保留 `ROUTE_EXISTS` 地点与 `previousRouteExpired` 上下文，下一轮补齐端点后继续同一查询；H5 恢复轮询遇到会话 404 时停止轮询并清理失效会话。`passenger` 225 项、`passenger-api` 249 项通过；乘客 H5 `npm test`（39 条 API 契约 + 2 条恢复逻辑）和 `npm run build` 通过。未重启后端服务，真实页面复验仍待服务更新。

## 5. 第一期发布前仍需完成的运行环境与端到端验收

- 在 Nacos 的 `map-service-local.yml` 和 `passenger-api-local.yml` 注入同一个 `MAP_INTERNAL_TOKEN`，验证漏配、占位值和两端不一致均能快速失败或返回稳定错误码。
- 核验 Redis 连接、5 分钟 TTL、缓存丢失、条件版本变化以及服务重启后的追问行为。
- 分别配置 DashScope、DeepSeek 和高德密钥；验证模型超时、限流、空响应、地图超时、无可达路线和真实 POI 歧义。
- 使用真实收费站、服务区和加油站样本核验导航到达事件、入口／出口角色、收费站事件前后道路状态、已在高速主线的语义冲突及证据不足结果；特别复验五常收费站场景不得把驶出路线回答为从入口驶入。
- 通过 Gateway 验证 SSE 心跳、90 秒边界、客户端主动断线、反向代理空闲超时以及刷新页面后的历史恢复。
- 核对实际 Passenger 数据库：新环境使用 `passenger_schema.sql`；存量环境按顺序执行并记录 `passenger_ai_conversation_patch.sql` 和必要的 `passenger_ai_message_drop_customer_id_patch.sql`，不能只依赖 `CREATE TABLE IF NOT EXISTS` 升级旧表。

## 6. 第一期范围外目标契约的门禁

- `PassengerRouteOptionCache` 未装配；开放式多方案不纳入第一期且暂不开发。第一期不得展示或接受 `route.options`、`routeOptionId`，也不得对“可以经过哪些收费站”返回虚构的多方案。
- 将来若单独启动开放式方案开发，必须同步 PRD、TECH、API、本 TEST、前后端契约检查和相应自动化，另行记录真实运行环境验收结果。
