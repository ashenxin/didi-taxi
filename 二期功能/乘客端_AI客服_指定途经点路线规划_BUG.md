# 乘客端 AI 客服 · 指定途经点路线规划 — 代码审查 Bug 清单

- **审查日期**:2026-09-18
- **审查范围**:AI 客服路线规划全链路(map-service AI 内部接口 / passenger-api 轮次编排与 SSE / passenger 会话持久化),含 GPT 修复后的工作区改动
- **审查方式**:只读审查,未改动任何代码
- **结论**:原代码审查共核实 **15 个问题**(高危 7、中危 7、文档 1),另有 2 个候选经查证后排除；2026-09-21 真实会话回归另发现并修复 1 个收费站通行方向误判

当前交付结论：AI 客服第一期范围内的代码开发及上述问题修复已完成；开放式收费站多方案不纳入第一期，暂不开发。本清单记录修复状态，不代表第一期运行环境、真实地图和端到端验收已经完成。

## 2026-09-21 现状复核与修复结果

- **本轮已修复**:BUG-01、BUG-02、BUG-03、BUG-04、BUG-08、BUG-09、BUG-10、BUG-12、BUG-13、BUG-14、BUG-15。
- **按真实交付范围收口**:BUG-05。起终点、单端点续接、当前路线核查和指定途经点的地点选择缓存已经接入生产流程；仅开放式路线方案缓存仍不注册为生产 Bean，API/TECH 明确标为后续目标契约。
- **复核前已由工作区改动修复**:BUG-07、BUG-11；本轮只验证，没有重复修改。
- **本轮已修复**:BUG-06。会话列表、请求状态和删除会话已在 passenger-service、passenger-api、权限解析和乘客 H5 接通；逻辑删除会撤销活动请求，complete／fail 在重放前拒绝已删除会话。
- **验证结果**:上一轮三个模块全量基线为发现 579 个、实际执行 578 个，失败和错误均为 0。本轮 map 聚焦 40 个、passenger-api 聚焦 82 个、passenger 会话数据库集成 26 个测试通过，前端契约检查和构建通过。收费站方向误判修复后，map 模块全量发现 136 个、实际执行 135 个，失败和错误均为 0，1 个外部模型手动测试按设计跳过；最近入口匝道合并步骤修复后的 map 聚焦测试已通过，但该修改后的全量回归，以及 passenger 与 passenger-api 全量回归尚未重新执行。正式验收口径见同目录专项 TEST。

下文的“问题”与“影响”保留 2026-09-18 审查时的证据快照；每条的当前结论以“当前状态”和上方 2026-09-21 复核结果为准，不应再把已修复项解读为现存缺陷。

---

## 2026-09-21 真实会话回归

### BUG-16 “到达收费站”被错误当作从同名入口驶入高速

- **当前状态**:已修复。当前路线核查会定位同一路线中的目标收费站到达事件，并比较事件前后的有序道路状态：普通道路到高速才证明驶入，高速到普通道路才证明驶出；方向相反返回 `TOLL_ACTION_DIRECTION_CONFLICT`，证据不足返回不可确认。
- **复现场景**:路线步骤显示车辆先沿 G25 长深高速行驶，到达五常收费站后进入普通道路；乘客核查“五常收费站(G25长深高速入口北向)”时，旧逻辑仅因存在同名“到达收费站”事件且所选 POI 名称含“入口”，错误回答会从该入口驶入高速。
- **根因**:`WaypointRouteCandidateEvaluator` 证明的是路线到达目标收费站，不包含驶入／驶出方向；`CurrentRouteRelationEvaluator` 却把所选 POI 名称中的“入口／出口”当成已发生的动作，`CurrentRouteSemanticConsistencyEvaluator` 又在到达事件匹配后提前返回有效，跳过了事件前后道路状态核验。
- **验证**:既有 `CurrentRoutePassageServiceTest` 新增反向冲突与正确出口两个场景，相关 map 聚焦 18 个测试通过；既有 `AiRouteTurnServiceTest` 新增 BFF 回答文案回归，34 个测试通过。map 模块全量发现 136 个、实际执行 135 个，失败和错误均为 0，1 个外部模型手动测试跳过。

---

## 🔴 高危问题

### BUG-01 map.internal-auth.token 默认空串且无启动校验

- **当前状态**:已修复。passenger-api 已将 map 内部 token 纳入非宽松环境启动校验，map 鉴权失败会返回稳定 `AI_INTERNAL_AUTH_FAILED`；配置与 TEST 已同步。
- **位置**:`passenger-api/.../config/MapInternalClientProperties.java:8`、`passenger-api/.../config/PassengerSecurityStartupValidator.java`
- **问题**:新 map 内部鉴权 token 默认空字符串,是唯一一个未被 `PassengerSecurityStartupValidator` 校验的客户端 token(现仅校验 `PASSENGER_INTERNAL_TOKEN`、`ORDER_LIFECYCLE_INTERNAL_TOKEN`);而 map 侧 `MapInternalSecurityStartupValidator` 对同一 key 强制要求 ≥32 字节非占位值,否则拒绝启动。该 key 在仓库任何 yml / 测试配置 / 文档中均不存在。
- **影响**:非本地/开发环境漏配时,passenger-api 静默启动、每次内部调用带空 token 被 map 侧 401/403 拒绝(且无 `X-Ai-Error-Code`),每个轮次都以 `turn.failed` / `AI_INTERNAL_ERROR "服务暂时不可用"` 收场——整个功能无声死亡,无快速失败、无可区分遥测。
- **修复方向**:`PassengerSecurityStartupValidator` 增加对 `map.internal-auth.token` 的校验;提供 Nacos/yml 配置示例;map 侧鉴权拒绝时返回 `X-Ai-Error-Code`。

### BUG-02 SSE 超时等于轮次预算,且无心跳

- **当前状态**:已修复。SSE 连接上限调整为 90 秒，每 15 秒发送 `ping`，请求状态端点也已实现，相关单测与 API/TECH 口径已对齐。
- **位置**:`passenger-api/.../controller/PassengerAiConversationController.java:86`、`passenger-api/.../service/SseAiTurnEventSink.java:53-55`
- **问题**:`SseEmitter` 超时 60s 恰好等于整个轮次预算;从不发送 ping 心跳;`onTimeout`/`onError` 只打日志。
- **影响**:最坏路径约 70s(30s 抽取 + 两次顺序高德 POI 查询各 5s connect / 15s read)已超过 60s 上限。慢但健康的轮次被 Spring 在 60s 处完成 async 请求(spring-webmvc 6.2.19 已验证该行为),乘客只看到 `turn.started` 后流即结束;worker 继续跑完并把 COMPLETED 答案落库,乘客却再看不到。文档要求的 ping 心跳(API.md:158/171、TECH.md:111)从未实现,两个文档化断线恢复路径只剩历史读取一条。
- **修复方向**:emitter 超时 > 轮次预算(如 90s)+ 定期 ping 心跳;`onTimeout` 补发可区分的终止事件;补 request-status 端点(见 BUG-06)。

### BUG-03 新增的 422 AI_ROUTE_NOT_FOUND 实际不可达

- **当前状态**:已修复。高德“无可达路线”类 info code 与空 paths 均映射为 `AI_ROUTE_NOT_FOUND`，并已补对应服务测试。
- **位置**:`map/.../service/AmapDrivingRouteService.java:106/115`、`map/.../internal/AiRouteInternalController.java:102-105`
- **问题**:高德对真实"无路可达"返回 `status=0` + info code(如 `NO_ROADS_NEARBY`/20801),落入通用 `AmapApiException` 分支 → 映射为 503 `AI_MAP_UNAVAILABLE`;而新增的 `AmapRouteNotFoundException`(115 行,422 `AI_ROUTE_NOT_FOUND` 的唯一生产者)需要 `status=1` 且 `paths:[]` 才会触发——高德对这类场景不返回该形态;且无服务层测试桩覆盖空 paths payload。
- **影响**:"换地点就能解决"的场景被报成地图故障,乘客永远收到"地图算路暂时不可用,请稍后重试"并无限重试,而非文档约定的 `AI_ROUTE_NOT_FOUND` / "当前起终点未找到可达路线,请更换地点后重试"(`AiRouteTurnService:424` 已实现该分支但永远走不到)。
- **修复方向**:将 `status=0` 且 info code 属于"无路线"类(如 `NO_ROADS_NEARBY`)的响应映射为 `AmapRouteNotFoundException`(422);补服务层测试。

### BUG-04 轮次线程池配置与停机等待双重问题

- **当前状态**:已修复。轮次执行器已调整并对拒绝提交输出稳定失败事件，停机等待覆盖轮次窗口，passenger-service 已增加失联活动请求定时对账。
- **位置**:`passenger-api/.../config/AiTurnExecutorConfiguration.java:14/18`
- **问题**:
  - (a)`corePoolSize=2` / `maxPoolSize=8` / `queueCapacity=64`:队列未满前 `ThreadPoolExecutor` 不会扩到 core 以上,实际只有 **2 个轮次并发**、64 个排队;未配置拒绝策略,第 65 个并发提交抛 `TaskRejectedException`(AbortPolicy)——此时 emitter 已创建(60s 时钟已开始走)但尚未返回,落入 catch-all `@ExceptionHandler(Exception.class)`,对 text/event-stream 请求返回 HTTP 500 JSON,且无任何 `turn.failed` 落库;排队的 64 个 emitter 也会在轮次开始前就已超时。
  - (b)`setWaitForTasksToCompleteOnShutdown(true)` + `setAwaitTerminationSeconds(30)` 只有 60s 轮次预算的一半:SIGTERM 时中断进行中轮次,不发任何终止事件;`active_request_no`/`active_request_started_at` 保持占用;无定时清扫(TECH.md:118 承认只能靠下次 begin 拿锁对账),乘客消息长时间无回复,直到 ≥60s 后下次消息触发 `expireUnfinishedRequest` 写 `AI_REQUEST_EXPIRED`。
- **修复方向**:(a)扩大 core 或改用小队列/SynchronousQueue 使 max 生效,配置拒绝策略或 controller 层兜底;(b)`awaitTerminationSeconds` ≥ 60,并补启动对账/定时清扫。

### BUG-05 三个短时缓存类共 895 行零生产调用点,文档却宣称已交付

- **当前状态**:已按真实范围收口。`PassengerRoutePlaceChoiceCache` 已接入起终点、当前路线核查及指定途经点生产链路；`PassengerRoutePartialEndpointCache` 已装配并接入单端点续接；`PassengerRouteOptionCache` 仍不装配为生产 Bean，开放式收费站方案在 PRD/TECH/API/TEST 中明确为未实现。
- **位置**:`map/.../ai/service/PassengerRouteOptionCache.java`(及 `PassengerRoutePlaceChoiceCache`、`PassengerRoutePartialEndpointCache`)
- **问题**:三个缓存类(共 895 行 + 内部 record)在 map / passenger-api / passenger 主源码中无任何调用点;Redis key 前缀(`map:ai:route:options:v1:` 等)仅出现在定义文件。controller(`AiRouteInternalController:45-52`)只注入 extraction/preparation/confirmation/defaultRoute/routeCardAssembler/snapshotCache。但本次改动同时修改的 TECH.md(43/97/119 行)与 API.md(138/144/254-257 行)声明"单端点补齐、地点澄清选项、收费站路线方案索引"已随短时层交付,并定义了 `AI_PLACE_CHOICE_NOT_FOUND/EXPIRED`、`AI_ROUTE_OPTION_NOT_FOUND/EXPIRED` 错误码——这些错误码在任何枚举、controller 中都不存在。
- **影响**:地点澄清/途经点轮次实际落到 DEFAULT_ROUTE 且无候选列表;这些 bean 启动即实例化但无任何测试覆盖;联调按文档调用不存在的端点会直接失败。
- **修复方向**:二选一——实现 place-choice / route-option / partial-endpoint 流程与端点,或从 TECH/API 文档中移除未交付的声明与错误码。

### BUG-06 文档 5 个公开路径只实现 3 个,未实现路径返回 503

- **当前状态**:已修复。会话列表、请求状态和删除会话 3 个公开路径已有 Controller、BFF、内部服务、权限规则和 H5 调用；跨乘客读取按不存在处理，删除为幂等逻辑删除并阻断迟到结果。
- **位置**:`二期功能/乘客端_AI客服_指定途经点路线规划_API.md:81`、`passenger-api/.../auth/action/PassengerActionResolver.java:66-68`
- **问题**:API 文档列出 5 个会话路径,但 `GET /app/api/v1/ai/conversations`、`GET .../conversations/{no}/requests/{requestNo}`、`DELETE .../conversations/{no}` 无任何 controller 映射;`PassengerActionResolver` 只有 3 个已实现路径的规则,未解析的 action 被 `PassengerJwtAuthFilter:154-158` 以 HTTP 503 "暂时无法确认该操作权限" 拒绝;passenger-service 亦无 list / request-status / delete 实现。AGENTS.md:48 要求接口变更时同步文档。
- **影响**:按文档实现的客户端收到 503,与真实故障不可区分,易被误报为线上事故;缺失的 request-status 端点同时丢掉文档承诺的两条断线恢复路径之一。
- **修复方向**:实现或删除文档路径;至少让未实现路径返回 404 或明确的 NOT_IMPLEMENTED 错误码,而非 503。

### BUG-07 删除 DashScope 依赖但 PRD/README/AGENTS 仍声明可用

- **当前状态**:复核前已修复。map 已恢复 `spring-ai-alibaba-dashscope` 并通过独立适配器用于当前路线提取；DeepSeek `ChatModel` 依赖保留给未来 Agent 工厂，各份文档已按此区分。
- **位置**:`map/pom.xml:52`、`乘客端_AI客服_指定途经点路线规划_PRD.md:401`、`README.md:85`、`AGENTS.md:17`
- **问题**:本 diff 删除了 `spring-ai-alibaba-starter-dashscope` 及唯一测试它的 `DashScopeModelAccessIntegrationTest`;但三份文档仍声明百炼(DashScope)保留、`DASHSCOPE_API_KEY` 可注入。现 classpath 只剩 `spring-ai-starter-model-deepseek`(其自动配置断言非空 api key)。
- **影响**:按文档只配 `DASHSCOPE_API_KEY` 部署时,map 服务 `BeanCreationException` 起不来,`DASHSCOPE_API_KEY` 完全无作用;构建无任何告警提示。
- **修复方向**:更新 PRD/README/AGENTS 移除 DashScope 声明,或在 pom 保留该依赖。

---

## 🟡 中危问题

### BUG-08 fail() 幂等重放不校验已存在消息状态

- **当前状态**:已修复。`fail()` 对已存在 `COMPLETED` 结果按幂等成功重放，不再对同一 requestNo 发出相互矛盾的失败事件。
- **位置**:`passenger/.../ai/service/AiConversationService.java:283`(及 282-285 事务外重放、290-294 锁内重放)
- **问题**:`fail()` 的两处幂等重放都不校验已存在 ASSISTANT 消息的状态,与 `complete()` 所有重放必经 `completeView()`(414 行强制 COMPLETED)的守卫不对称。
- **影响**:轮次已落库 COMPLETED 但 HTTP 响应丢失 → BFF 补调 `fail()` → 查到 COMPLETED 行后直接返回"失败已记录",不写 FAILED 行、不抛冲突 → 乘客收到 `turn.failed`,而消息列表里同一 requestNo 是 COMPLETED 的 ROUTE_CARD;同 Idempotency-Key 重发又重放出 `route.card + turn.completed`——同一轮次先失败后成功,直接违反该类 javadoc:35 的承诺。集成测试只覆盖 complete 侧的对称场景。
- **修复方向**:`fail()` 重放时校验已存在消息状态,COMPLETED 时按幂等成功返回(或抛冲突),与 `complete()` 对称。

### BUG-09 短时缓存过期抛 IllegalStateException,无对应 handler

- **当前状态**:已修复。默认路线与确认流程的条件失效统一返回可识别的上下文失效结果，BFF 转为重新提供起终点的追问。
- **位置**:`map/.../ai/service/PassengerDefaultRouteService.java:57`(同形:99/101 行、`PassengerRouteConfirmationService.java:62`)
- **问题**:conditions 缓存 TTL 恰好 5 分钟(`PassengerRouteConditionsCache:30`),BFF 在 /confirm 与 /default 之间复用持久化的 conditionVersion(`AiRouteTurnService:150-157`),第二次调用越过 TTL 时 `conditionsCache.find` 为空 → 57 行抛 `IllegalStateException("已确认起终点不存在或已失效")` → map 无该异常 handler → HTTP 200 envelope code 500、无 `X-Ai-Error-Code` → unwrap 抛 `BizErrorException(502)` → 乘客收到 `AI_INTERNAL_ERROR "服务暂时不可用"`,而非文档约定(API.md:271 / TECH.md:41)的"缓存过期转为 answer.completed 重新询问"。且 confirmation 内同版本不匹配的兄弟路径返回 `CONTEXT_UNAVAILABLE`,同一条件两条路径行为不一致。
- **修复方向**:新增业务异常 + handler(带 `X-Ai-Error-Code`,如 CONTEXT_UNAVAILABLE 或条件过期码),统一版本冲突与过期两条路径的行为。

### BUG-10 模型调用超时后弃管:不 cancel、无执行器、null 误报

- **当前状态**:已修复。模型提取使用有界专用执行器，超时取消 future，并将执行器已满、空结果和上游异常分类为稳定错误码。
- **位置**:`map/.../ai/service/PassengerRouteExtractionService.java:62-74`
- **问题**:`chatModel.call` 经 `CompletableFuture.supplyAsync` 派发(无显式执行器),30s `.get(modelTimeout)` 超时后没有任何 `cancel`,Spring AI 调用无客户端读超时,其重试默认值(max-attempts=10、backoff 2s×5)让弃管调用继续重试数分钟。可见 CPU ≤2 时 ForkJoinPool 并行度 1,supplyAsync 走 ThreadPerTaskExecutor → 每次超时泄漏一个非池化平台线程(≥3 CPU 时则占 commonPool worker)。另 `ChatResponse.getResult()` 对空列表返回 null → NPE 落入 74 行 `catch(Exception)` 被误报为 `AI_PROVIDER_UNAVAILABLE`(503)。
- **修复方向**:专用执行器 + 超时后 `future.cancel(true)` + 客户端读超时/关闭重试;对 null result 判空并区分错误码。

### BUG-11 JSON 兜底解析只取最后一个 `{`

- **当前状态**:复核前已修复。提取层会识别围栏或文字包裹的第一个完整 JSON 对象，全空结果不会被当作有效提取。
- **位置**:`map/.../ai/service/PassengerRouteExtractionService.java:108-110`
- **问题**:整段解析失败后,兜底只重试从最后一个 `{` 开始的括号组。模型输出"正确 JSON + 含花括号的散文"(如 `{"originName":"德清",...} （示例：{"A"}）`)时,解析出 `{"A"}` → 得到全 null 的 `ExtractEndpointsResponse`(非 null 对象,不会走全 null 哨兵分支)→ 抽取实际成功却被当作失败。
- **影响**:BFF 的 null-endpoint 检查(`AiRouteTurnService:184`)让乘客重新描述起终点,且无任何告警日志。
- **修复方向**:优先尝试第一个 `{` 到最后一个 `}` 的区间;解析仍失败时记录原始输出告警。

### BUG-12 新增 IllegalArgumentException handler 对 @RequestBody DTO 不可达

- **当前状态**:已修复。`HttpMessageNotReadableException` 及其 DTO 构造校验原因已映射为 HTTP 400 和稳定 `AI_INVALID_REQUEST`，catch-all 也携带稳定 AI 错误码。
- **位置**:`map/.../common/exception/GlobalExceptionHandler.java:73`
- **问题**:record 紧凑构造器里的校验 IAE 被 Jackson 包装为 `ValueInstantiationException` → `HttpMessageNotReadableException`,落入 80 行 catch-all → HTTP 200 {"code":500,"msg":"服务器繁忙"} 而非 400;即使命中(如 /prepare 的 model 派生 originName 超 80 字符,`PassengerRouteEndpointPreparationService:93`)也不返回 `X-Ai-Error-Code` 头。
- **影响**:BFF 的 unwrap(406)/classify(439) 得到通用 `AI_INTERNAL_ERROR`,坏输入与故障不可区分,破坏 API.md:271 承诺的"真实 HTTP 状态 + `X-Ai-Error-Code` 稳定错误码"契约。
- **修复方向**:新增 `HttpMessageNotReadableException` handler(区分 DTO 校验 IAE → 400 + `X-Ai-Error-Code`);catch-all 也补 `X-Ai-Error-Code`。

### BUG-13 execute() 捕获 Throwable,且 catch 体自身可逃逸

- **当前状态**:已修复。轮次编排只捕获 `Exception`，JVM `Error` 不再伪装成普通业务失败；失败落库异常也不会跳过终止事件。
- **位置**:`passenger-api/.../service/AiRouteTurnService.java:133`、`460-467`
- **问题**:`execute()` 捕获 `Throwable`,把 StackOverflowError / OutOfMemoryError 等 JVM Error 当作普通轮次失败 warn 落库、报告为 `AI_INTERNAL_ERROR` 并继续复用线程;且 catch 体自身可逃逸——`failQuietly` 只 `catch(Exception)`,Error 在其 Feign 调用内发生时,140 行 `sink.turnFailed` 被跳过,controller 的 finally 只关流,乘客得不到任何终止事件。
- **修复方向**:改为 `catch(Exception)`,让 Error 自然抛出;`failQuietly` 用更宽捕获或 try/finally 保证终止事件必发。

### BUG-14 MapRouteAiClient 使用 Feign 默认 60s 读超时

- **当前状态**:已修复。map 内部 Feign 客户端已配置显式连接和读取超时，并通过配置类约束在 SSE 窗口内。
- **位置**:`passenger-api/.../config/MapInternalFeignConfiguration.java:9`
- **问题**:该配置只提供 token 拦截器,无 `Request.Options`;无全局 `Request.Options` bean、无 yml 配置 → `MapRouteAiClient` 用框架默认 connect 10s / read 60s;而兄弟 `OrderLifecycleFeignConfiguration` 对同类内部调用设 300/800ms。
- **影响**:read 60s 恰好等于 BFF 的 SSE 超时与 passenger 的 stale 窗口 → 停滞的 map 调用占满整个轮次,永远无法在窗口内被上报为 `turn.failed`;一个轮次里的多次调用可各耗 60s。
- **修复方向**:为 `MapRouteAiClient` 配置显式 `Request.Options`,对齐内部调用惯例(如 connect 300ms、read 按轮次预算折算)。

---

## 📄 文档问题

### BUG-15 TECH.md 状态与范围声明与代码自相矛盾

- **当前状态**:已修复。TECH 已按实际生产链路记录长期会话、BFF 确定性编排、短时地图上下文、现有测试与待环境验收边界。
- **位置**:`二期功能/乘客端_AI客服_指定途经点路线规划_TECH.md:7/10/126`
- **问题**:TECH.md 称"端到端编排尚未实现"(7 行)、"长期会话持久化实现、新测试类留待短时层代码审阅后处理"(10/126 行),但工作区已新增 passenger-api 的 `AiRouteTurnService` 端到端编排、passenger 的 `AiConversationService` 会话/消息持久化(含 mapper XML 与 DDL)及 `AiConversationServiceIntegrationTest` 等新测试类。
- **影响**:评审/测试负责人按文档范围判定这些关键路径(事件顺序、begin/complete/fail 幂等、活动请求接管、分页历史归属校验)"尚未实现",跳过评审与验证;该结论若被写入发布说明/TEST 文档,会误导干系人。
- **修复方向**:更新 TECH.md 状态行与范围声明为"已实现、待评审"。

---

## ✅ 已核查并排除的候选(不成立)

1. **H2 列大小写问题** — 实测 `JdbcTemplate.queryForMap` 返回大小写不敏感 map,集成测试 21/21 通过,不成立。
2. **仓库级 Redis 配置缺失** — 属于文档化的 Nacos 配置约定,不成立。

---

## 修复优先级建议

1. **第一批(部署与基本可用)**:BUG-01(部署即死)、BUG-04(并发/部署中断)、BUG-02/03(乘客体感最直接的失败路径)
2. **第二批(幂等与错误码契约)**:BUG-08、BUG-09、BUG-12
3. **第三批(资源与超时)**:BUG-10、BUG-11、BUG-13、BUG-14
4. **第四批(文档与实现对齐)**:BUG-05、BUG-06、BUG-07、BUG-15 —— 每条均需"补实现"或"改文档"二选一
