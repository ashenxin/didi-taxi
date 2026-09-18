# 乘客端 AI 客服：路线核查与指定途经点规划 API

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v2.5 |
| 日期 | 2026-09-18 |
| 状态 | 与 PRD v1.10、TECH v2.5 对齐的目标接口；map-service 短时缓存基础类已实现，端到端接口尚未实现 |
| 产品范围 | 当前路线核查、指定地点路线卡片、收费站路线方案查询与选择 |
| 本轮范围 | 实现 map-service 短时地图上下文；持久化代码及新测试类待后续审阅 |

## 1. 契约原则

1. 每轮路线问答必须在本客服会话中取得乘客明确确认的起点和终点。新会话不继承地图页的起终点、路线或导航状态；首次给出两端但未明确确认时，服务端复述具体地点并等待确认，缺任一项先追问。首条消息已明确确认两端、且地图可唯一确定对应地点时，本轮直接处理原始意图，不要求再回复“对”。确认前不进行驾车算路、经过判断或收费站方案查询；同一会话只沿用仍有效的地图服务临时上下文，缓存失效后重新确认两端。
2. “这条路线是否经过”必须指向本客服会话已展示、仍可从地图服务解析的真实路线。客户端只提交聊天文字，不提交 currentRouteRef、折线、POI ID、导航点或时间作为事实。没有有效客服路线而起终点已由乘客确认时，先生成并明确展示本轮地图默认路线，再核查它。
3. 指定一个明确地点后，地图验证成功就直接返回完整 route.card 和这条路线预计时间，不要求乘客提交 routeCandidateId，也不计算与原路线的差值。
4. 乘客主动问“可以经过哪几个收费站”时才返回 route.options，乘客用 routeOptionId 选定一个有效方案后取得完整 route.card。地点身份歧义时另用 place.choices 和 placeChoiceId 澄清，二者不能混用。
5. 模型负责理解和解释；地图及服务端确定性校验负责地点、实际经过、路线和分钟数。合法绕行、掉头、与旧路线方向相反不是拒绝条件。仅靠近 POI 中心点不能当作实际到达；乘客明确要求进入、上高速或下高速时须验证对应动作。
6. 交付到问答结论或路线卡片和预计时间为止；不启动导航、不下单、不追踪实际出行。

以下路径和字段是待实施的目标契约。地图层已有路线验证代码和短时缓存基础类，公开接口与端到端编排尚未完成；旧 TEST 仍有必选候选流程，不能当作本版已实现接口。全量 `passenger_schema.sql` 和增量 `passenger_ai_conversation_patch.sql` 都只定义会话表与消息表，不创建旧路线任务表；旧表在各环境是否已删除需分别核验。已建消息表移除冗余 `customer_id` 使用独立迁移；其他明确字段缺口再提出最小增量迁移。

## 2. 认证、归属与公共类型

公开接口经 Gateway 到 passenger-api。Gateway 移除客户端自带 X-User-Id，再依据登录凭证注入真实乘客 ID；内部调用使用 X-Internal-Service-Token，并贯穿 X-Request-Id。写接口携带长度不超过 128 字符的 Idempotency-Key；SSE 接口另携带 Accept: text/event-stream。会话、路线引用及选择项都按认证乘客核对，归属不匹配按不存在处理。

### 2.1 已确认地点

地点业务类型只允许 TOLL_STATION、SERVICE_AREA、GAS_STATION。服务端地点快照保留真实地图地点身份和可通行导航点；对模型与客户端只输出必要的名称、地址、类型和适合展示的经过方式。客户端不能用这些展示字段覆盖服务端快照。

### 2.2 RouteCard

成功卡片的数据来自真实地图与通过验证的服务端结果。示意结构：

~~~json
{
  "routeRef": "RR-example",
  "origin": {"name": "德清高速路口"},
  "destination": {"name": "西溪湿地"},
  "via": {"name": "五常收费站", "businessType": "TOLL_STATION", "passage": "PASSED"},
  "route": {
    "coordinateSystem": "GCJ02",
    "distanceMeters": 45600,
    "durationSeconds": 3300,
    "polyline": [{"longitude": 120.1, "latitude": 30.2}]
  },
  "generatedAt": "2026-09-17T10:00:00+08:00",
  "expiresAt": "2026-09-17T10:05:00+08:00"
}
~~~

数值和地点仅为结构示例，不代表德清至西溪湿地的真实地图结果。durationSeconds 是本路线预计行驶时间，不含用户在服务区或加油站停留时间。经指定地点的成功卡片必须有已验证的 via；普通起终点默认路线的 via 为 null。实时成功卡片须有完整可展示的 polyline；历史卡片过期后省略 polyline，并标记 expired。卡片不包含新旧路线差值、是否下单或实际出行状态。

routeRef 是地图服务生成的随机、不透明路线快照引用，可随新卡片展示，并作为该客服消息的展示摘要保存，但乘客后续请求不回传它。passenger-api 从本会话最新一张已持久化的路线卡片取得当前 `routeRef`，再由 map-service 校验归属、条件版本和有效期；不另存 `currentRouteRef` 状态。消息中的旧 `routeRef` 不能在缓存过期后恢复路线。尚未选择的 route.options 不改变当前路线，客服卡片也不修改地图页路线。

若乘客问“这条路线经过 XX 吗”，但本会话此前没有可核查的路线，服务端在起终点明确后新建默认路线并以 route.card 返回，事件 payload 还应带 routeCheck，包含该卡片的 routeRef、核查地点和 PASSED、NOT_PASSED 或 UNVERIFIABLE；说明文字必须明确核查的是本轮新生成的默认路线。

### 2.3 核查与选项结果

当前路线核查结果包含已展示卡片的 routeRef、地点摘要和 verdict，verdict 只允许 PASSED、NOT_PASSED、UNVERIFIABLE。PASSED 和 NOT_PASSED 均针对这条具体路线；地图证据或地点身份不足时必须使用 UNVERIFIABLE，不把另一条可达路线当作当前路线。

给定起终点的“有没有经过 XX 的路线”使用 FOUND、NOT_FOUND_IN_QUERY、UNVERIFIABLE，避免把本次未找到说成所有道路都不存在。

收费站方案摘要包含 routeOptionId、收费站摘要、durationSeconds、isMapDefault、expiresAt；有对应的服务端完整路线快照，但选项列表不必发送每条完整折线。地点澄清摘要包含 placeChoiceId、名称、类型、城市或地址、expiresAt；不发送原始 POI ID、坐标和高德 JSON。

## 3. 客服会话内建立路线与当前路线引用

本期不提供地图页路线转交客服的 route-previews 公开接口。当前地图页使用的 /api/v1/map/route 及其页面状态不进入客服。新客服会话从空起终点和空当前路线开始；乘客在聊天文字里给出两端但未明确确认时，客服复述并等待确认。首条消息已明确确认两端且地图唯一定位时本轮即可继续。地图地点身份需要澄清时先说明城市或地址，不能静默选第一条。只有经乘客确认且地图身份明确的起终点才能用于向 map-service 请求驾车路线并展示 route.card。

### 3.1 建立本会话默认路线

乘客发送“帮我规划一条从德清高速路口到西溪湿地的路线”时，客服识别两端后先返回文字：“起点是德清高速路口，终点是西溪湿地，对吗？”必要时补充城市或地址；此轮不返回路线卡片。确认提问由 passenger-service 写成客服消息，待确认的地图地点身份及原始规划意图只在 map-service 短时缓存。乘客下一轮回复“对”或修正地点时，passenger-service 先保存乘客消息；编排层核对仍有效的待确认上下文与回复，确认认可无误才让 map-service 获取真实地图路线、生成 routeRef 并缓存完整路线与导航步骤。最终客服路线卡片及展示摘要也作为消息保存；这张已持久化的卡片成为后续“这条路线”的来源。地图失败时客服的说明仍存为消息；缓存失效则重新确认两端，不能仅靠聊天摘要恢复旧地点。最终 route.card 的 via 为 null，routeRef 与缓存中的同一条路线对应。上述例句和结果都不预设地图一定走三墩收费站。

### 3.2 后续核查与失效

乘客随后问“这条路线经过五常收费站吗”，passenger-api 核对会话归属后，从本会话最新一张已持久化的路线卡片读取 `routeRef`，再由 map-service 解析同一条路线。map-service 核对乘客、会话、条件版本、路线完整性和有效期；不能用重新规划的另一条路线冒充已有路线。引用或上下文失效时旧路线不能继续核查；客服先在原会话复述历史卡片中可读的起终点名称并询问是否仍以这两端为准；若历史不能说明两端，则要求乘客重新说出完整起终点。旧卡片有指定途经点时还需确认本轮是否继续要求经过该点，不得无声改成默认路线。客服问题及乘客的确认或修正回复都由 passenger-service 保存。map-service 重新查询并核对本轮地点后，才生成并展示新路线进行核查，并明确说明核查对象已更新。完整缓存暂定 5 分钟；历史卡片过期后仅保留文字、里程和预计时间，不再提供完整折线。乘客在新消息中直接明确确认完整地点且地图唯一确定时，可直接按该消息继续，不额外重复追问。

## 4. 对话公开 API

### 4.1 会话与历史

保持以下公开路径：POST /app/api/v1/ai/conversations 创建会话；GET /app/api/v1/ai/conversations 查询列表；GET /app/api/v1/ai/conversations/{conversationNo}/messages 查询历史；GET /app/api/v1/ai/conversations/{conversationNo}/requests/{requestNo} 查询请求状态；DELETE /app/api/v1/ai/conversations/{conversationNo} 删除会话。

创建会话由当前认证乘客和 Idempotency-Key 幂等定位。每次新进入客服可创建新会话；主动打开历史会话才读取其历史。删除和账号注销后旧会话不可见，迟到结果不得写回。历史消息中的乘客消息返回原 `clientMessageNo`（该轮客户端 Idempotency-Key），供断流后与本地待发送消息精确合并；客服消息的该字段为 null。历史 route.card 持久保存说明文字、起终点和途经点名称、里程、预计时间及失效时间；完整折线只在地图服务缓存仍有效时可按归属临时读取。过期后历史卡片不展开完整折线，route.options 仅保留文字和各选项摘要且不可再选。分页大小上限建议 50。

过期历史卡片的 routeCard 示例；polyline 字段不存在，里程和时间仍可展示：

~~~json
{
  "messageType": "ROUTE_CARD",
  "content": "已找到经过五常收费站的路线，预计行驶55分钟。",
  "payload": {
    "routeCard": {
      "routeRef": "RR-example-2",
      "origin": {"name": "德清高速路口"},
      "destination": {"name": "西溪湿地"},
      "via": {"name": "五常收费站", "businessType": "TOLL_STATION", "passage": "PASSED"},
      "route": {"distanceMeters": 45600, "durationSeconds": 3300},
      "expiresAt": "2026-09-17T10:05:00+08:00",
      "expired": true
    }
  }
}
~~~

### 4.2 发送文字轮次

POST /app/api/v1/ai/conversations/{conversationNo}/messages/stream

请求头包含 Idempotency-Key、Accept: text/event-stream、Content-Type: application/json。正文示例：

~~~json
{
  "content": "帮我规划一条从德清高速路口到西溪湿地的路线"
}
~~~

content 去除首尾空白后长度为 1 至 1000。首次给出完整起终点但未明确确认时，本轮只返回复述与确认问题，passenger-service 将该客服提问持久化为消息。乘客用同一接口发送“对”或修正文字，该回复也先持久化；服务端只在 map-service 的待确认上下文仍有效、与最新确认问题对应时继续原意图，不能仅凭模型对“对”的解释或旧聊天摘要恢复地图地点。首条消息已明确确认两端且地图能唯一定位时，本轮直接执行原始意图，无须再发确认问题。起点或终点匹配多个地图地点时先澄清具体城市、地址，不能静默选第一条。地图临时上下文、路线或选项缓存失效时，先在同一对话中重新询问两端并持久化提问；乘客的确认或修正回复也持久化，随后 map-service 重新查询地图地点。若新消息本身明确确认完整两端且地图唯一定位，则直接处理。文字请求体只接收 content，不接收 pageContext、起终点结构、currentRouteRef、POI ID、导航点、折线、路线时长、routeOptionId 或任务状态。

起终点首次齐全但本轮未明确确认时，先返回确认问题；首轮已明确确认且地图唯一定位时，可在本轮产生以下路线业务事件：

| 意图 | 最终事件 | 主要结果 |
| --- | --- | --- |
| 起终点缺失、待确认或需修正 | answer.completed | 复述具体两端并等待乘客明确确认；本轮不返回路线事实 |
| 地图临时上下文或路线缓存失效 | answer.completed | 提醒重新确认起终点；客服提问和乘客后续回复均进入原会话历史，不凭旧卡片核查 |
| 核查本会话当前路线是否经过 | answer.completed；本会话尚无路线时为 route.card | 已展示路线的 routeRef 及 PASSED、NOT_PASSED、UNVERIFIABLE |
| 查询有无经过路线 | answer.completed | FOUND、NOT_FOUND_IN_QUERY、UNVERIFIABLE |
| 普通起终点规划 | route.card | 客服本轮生成的地图默认路线和预计时间 |
| 指定地点规划成功 | route.card | 一条已验证的新路线及自身预计时间 |
| 指定地点身份不明确 | place.choices | 有效地点选项 |
| 查询可选收费站路线 | route.options | 本轮已验证的不同收费站方案 |

“这条路线”没有有效本会话路线、但本轮起终点已明确确认且 map-service 临时上下文有效时，服务端可先生成并展示本轮默认路线，同时在 route.card 的说明中写明核查对象是这条新生成的路线。若旧路线或上下文已失效，先重新确认两端；无法建立真实路线时返回明确失败或追问。

首轮直接处理也按意图区分：`我确认从 A 到 B，必须经过 X，帮我规划` 在 X 唯一明确且路线通过验证后返回 route.card，不先生成默认路线；`我确认从 A 到 B，这条路线经过 X 吗` 在本会话没有路线时先生成并展示默认路线，route.card 同时带该路线的 routeCheck；`我确认从 A 到 B，有没有经过 X 的路线` 只返回 answer.completed 的存在性结论。若 X 指向多个地点，先返回地点澄清，不把地点搜索结果当作经过证据。

### 4.3 选择地点

POST /app/api/v1/ai/conversations/{conversationNo}/route-tasks/{taskNo}/place-choice/stream

请求体为 expectedRequestVersion 和 placeChoiceId。`taskNo`、地点选项及成员关系由 map-service 短时缓存，不要求 passenger-service 保存路线任务。passenger-api 先核对乘客和会话归属，map-service 再核对条件版本、有效期及选项成员关系，从可信地点快照继续原先被暂停的意图。乘客选择与客服结果都作为消息经 passenger-service 持久化。若原意图是指定地点规划，验证成功返回 route.card；若原意图是核查当前路线，已有有效路线时返回 answer.completed，没有有效路线但两端仍有效时先生成并展示默认路线，再以带 routeCheck 的 route.card 返回；若原意图是查询有无经过路线，则返回 answer.completed。此动作可能调用地图，沿用消息 begin、外部调用、complete 两段短事务；选项或上下文过期时要求重新确认两端。客户端不得提交 POI ID、坐标或入口出口。

### 4.4 选择收费站路线方案

POST /app/api/v1/ai/conversations/{conversationNo}/route-tasks/{taskNo}/route-option/selection

请求头包含 Idempotency-Key 和 Accept: text/event-stream。请求体：

~~~json
{"expectedRequestVersion": 2, "routeOptionId": "RO-example"}
~~~

只对 map-service 短时缓存中仍有效的路线方案选择生效。passenger-api 先核对会话归属，在数据库事务外让 map-service 按可信乘客、会话、条件版本、taskNo 和 routeOptionId 核对选项成员与有效期，并读取同一份完整路线快照；passenger-service 仅按会话消息的既有幂等和顺序规则保存乘客选择及客服卡片。成功后发送 route.card，不重新调用模型或高德算路。相同幂等键重试得到第一次结果；缓存过期或条件变化时先重新确认两端，再查询新方案。客户端不能提交 POI、折线、距离、时间或是否默认等字段。

指定地点规划分支不调用此接口，也不要求选择 routeOptionId。旧 routeCandidateId 不是本版所有规划的必填参数。

## 5. SSE 事件

所有流式接口采用 text/event-stream;charset=UTF-8；受理后先发送 turn.started，可发送 turn.status 和必要的 answer.delta，最终在结果成功持久化后发送一个完整内容事件，随后 turn.completed 并关闭流。模型未拿到地图验证结果前，answer.delta 不得提前声称已经过或报出预计分钟数。无业务事件时发送 ping；断线后用请求状态或历史读取最终结果的文字和里程、时间；完整折线只在地图缓存有效时重新读取，不依赖半截路线 JSON。

| 事件 | 用途 |
| --- | --- |
| turn.started | 请求号、已保存用户消息和当前版本 |
| turn.status | 受控业务阶段，不输出模型思维过程 |
| answer.delta | 仅已安全确定的自然语言增量 |
| answer.completed | 无卡片的完整问答、必要追问或无法验证说明 |
| place.choices | 完整地点澄清选项 |
| route.options | 完整收费站方案摘要集合 |
| route.card | 一张完整默认路线或经指定地点路线卡片，含预计时间 |
| turn.completed | 本轮成功结束 |
| turn.failed | 稳定错误码和可读说明 |
| ping | 心跳，不改变业务状态 |

内容事件的 assistantMessage 包含 messageNo、requestNo、role、messageType、content、payload、createdAt；route.card、route.options、place.choices 的 payload 分别按第 2 节类型给出。相同幂等请求完成后的重放不重新调用模型或高德；服务端只在地图快照仍有效时补出完整路线，否则返回已持久化的文字和里程、时间摘要及失效标记，再发送 turn.completed。若原请求已落库为失败，同一幂等键只重放原错误码和可读说明的 turn.failed，不发送成功内容事件或 turn.completed。

首次识别完整起终点但乘客尚未明确确认时，先以普通文字事件请求确认；该轮不包含路线卡片、经过结论或预计时间。客服确认提问先写入会话历史。乘客随后仍通过第 4.2 节的文字接口回复 `{"content":"对"}`，该回复也写入历史；map-service 仅在对应的待确认地点与原始意图仍处于有效短时缓存时继续，失效则重新询问两端：

~~~text
event: answer.completed
data: {"requestNo":"AIR-confirm-example","assistantMessage":{"messageType":"TEXT","content":"我理解的起点是德清高速路口，终点是西溪湿地，对吗？"}}
~~~

核查已有客服路线的最终事件示意，经过结论只对本会话当前路线生效：

~~~text
event: answer.completed
data: {"requestNo":"AIR-example","assistantMessage":{"messageType":"TEXT","content":"当前这条路线不经过五常收费站。","payload":{"routeCheck":{"routeRef":"RR-example","place":{"name":"五常收费站","businessType":"TOLL_STATION"},"verdict":"NOT_PASSED"}}}}
~~~

指定地点规划成功的最终事件示意；routeCard 使用第 2.2 节的完整结构，durationSeconds 是该新路线自身的时间：

~~~text
event: route.card
data: {"requestNo":"AIR-example-2","assistantMessage":{"messageType":"ROUTE_CARD","content":"已找到经过五常收费站的路线，预计行驶55分钟。","payload":{"routeCard":{"routeRef":"RR-example-2","origin":{"name":"德清高速路口"},"destination":{"name":"西溪湿地"},"via":{"name":"五常收费站","businessType":"TOLL_STATION","passage":"PASSED"},"route":{"coordinateSystem":"GCJ02","distanceMeters":45600,"durationSeconds":3300,"polyline":[]},"generatedAt":"2026-09-17T10:00:00+08:00","expiresAt":"2026-09-17T10:05:00+08:00"}}}}
~~~

收费站方案查询的最终事件示意。此处仅发送摘要；每个 routeOptionId 在服务端绑定完整已验证路线：

~~~text
event: route.options
data: {"requestNo":"AIR-example-3","assistantMessage":{"messageType":"ROUTE_OPTIONS","content":"本次找到两种可行方案，请选择一条。","payload":{"taskNo":"AIT-example","requestVersion":2,"expiresAt":"2026-09-17T10:05:00+08:00","routeOptions":[{"routeOptionId":"RO-1","via":{"name":"三墩收费站","businessType":"TOLL_STATION"},"durationSeconds":3000,"isMapDefault":true},{"routeOptionId":"RO-2","via":{"name":"五常收费站","businessType":"TOLL_STATION"},"durationSeconds":3300,"isMapDefault":false}]}}}
~~~

上述地点和分钟数均为接口格式示例，不是地图事实；route.card 示例中的空折线仅为省略，真实成功卡片必须携带完整可展示路线。选定 RO-2 后返回 route.card，而不是另发一个要求乘客再次选择的候选事件。

## 6. 内部 API 与可信上下文

### 6.1 passenger-service

保持创建会话、读取历史、读取有限模型记忆、删除会话，以及以下只围绕对话消息的短事务接口语义：

- POST /api/v1/internal/ai/conversations/{conversationNo}/turns/begin：在会话行锁内核对 customerId、会话归属、幂等键和单会话活动请求，同键并发仅一轮可以开始；按顺序保存本轮乘客消息，返回 requestNo、消息序号、有限历史及最新客服确认提问。活动请求超过接管阈值时先保存旧轮次失败消息，同键重试重放该失败，不再次执行模型或地图；不同键可开启新轮次，旧结果不能迟到覆盖。点击地点或路线方案的选择也应保存为乘客在该对话中的选择消息。
- POST /api/v1/internal/ai/conversations/{conversationNo}/turns/{requestNo}/complete：再次核对归属、活动请求、幂等与会话未删除状态；获取会话行锁后重查同一请求的客服消息，命中则重放已保存结果，否则按顺序保存本轮客服消息并释放活动请求。客服确认提问、地点候选、路线方案、核查结论和路线卡片都属于客服消息；卡片 `payload_json` 仅含展示摘要及 `routeRef`，不保存完整折线、导航步骤或权威地点身份。成功持久化后才发送对应的最终 SSE 事件。
- POST /api/v1/internal/ai/conversations/{conversationNo}/turns/{requestNo}/fail：获取会话行锁后同样重查同一请求的客服消息；已有结果则重放，否则保存客服可读的失败说明或稳定错误消息并释放活动请求。若地图缓存已写入但消息未完成，未引用的快照按短时有效期清理。
- POST /api/v1/internal/ai/route/replay：passenger-api 从已通过会话归属校验的路线卡消息取得 routeRef，连同可信 customerId 和 conversationNo 交给 map-service；地图服务按快照里的条件版本重验当前已确认条件、乘客、会话和有效期。响应的 routeCard 在可恢复时包含完整折线，不可恢复时为 null。BFF 重放 SSE 时统一输出 payload.routeCard；不可恢复时使用已落库的无折线摘要并标记 expired=true，不重新算路。

passenger-service 不新增已确认地点、待恢复意图、当前路线引用或路线任务的持久化业务状态。它保存乘客与客服实际发生的完整对话，利用现有会话和消息字段实现顺序、幂等、归属与删除。map-service 的地点、选项和路线缓存失效后，客服重新确认起终点的问题与乘客回复也通过上述消息轮次写入同一会话。最新卡片消息的 `routeRef` 可由服务端取出并交给 map-service 核验；过期消息中的地点名称和引用只能用于展示或组织重新确认的话术，不能充当地图地点或路线仍有效的证据。

### 6.2 map-service Agent 执行

POST /api/v1/internal/route-agent/stream 接收可信 customerId、requestNo、conversationNo、requestVersion、userText、有限 memory，以及 passenger-api 从已持久化消息提供的最新确认提问摘要。map-service 从自身仍有效的临时会话上下文取得已确认的地图地点、条件版本；若乘客本轮直接明确确认完整两端，则重新查询并唯一确定地图地点。待确认地点不能用于驾车算路。缓存失效时只生成重新确认两端的客服提问，不能从消息文字或模型摘要恢复旧坐标、旧路线。完整路线、地点或方案选项由 map-service 在服务端解析，不能来自客户端或模型。

map-service 输出 agent.status、agent.delta、agent.result 或 agent.failed。agent.result 使用互斥的 resultType：ANSWER、PLACE_CHOICES、ROUTE_OPTIONS、ROUTE_CARD；ROUTE_CARD 和 ROUTE_OPTIONS 的地图事实由 Java 服务写入结构化结果，模型只写解释文字。BFF 不依赖模型文本推断 resultType，也不接受模型输出的虚构路线作为最终卡片。

收费站方案发现及当前路线核查可以由 map-service 中确定性业务服务完成，不要求将原始 POI 查询或完整当前路线开放给模型。具体 Agent Tool 名称、接口类和方法签名在代码讨论时确定。

map-service 另需内部能力：按可信乘客 ID、会话编号短时保存已核对两端、待确认地点与意图及地点或路线选项；按有效两端生成并缓存真实默认路线；按内部 routeRef 和条件版本读取同一条完整路线以核查地点或重建有效期内的卡片；按 routeOptionId 解析已验证收费站方案的完整快照。缓存由 map-service 管理，Redis 依赖与短时缓存基础类已接入，运行环境连接配置仍待核验。随机 routeRef、地点和路线选项 ID 分别绑定乘客、会话、条件版本、地图事实及失效时间；读取时重验归属和时效。完整路线与相关临时上下文暂定 5 分钟有效，缓存丢失等同失效；客服先重新确认起终点，再重新查询和规划。地图服务不保存聊天历史，passenger-service 不持久化完整路线或地图临时上下文。

map-service 内部完整快照的基础字段为：`routeRef`、`customerId`、`conversationNo`、`conditionVersion`；服务端确认的 `origin`、`destination`（名称、地址或城市、可取得的地图地点 ID、GCJ02 坐标）；`provider`、`coordinateSystem=GCJ02`、`travelMode=DRIVING`、`routeKind=DEFAULT|VIA_PLACE`；`distanceMeters`、`durationSeconds`、按行驶顺序排列的完整 `polyline`、`navigationSteps`；以及 `generatedAt`、`expiresAt`。每个导航步骤保留 `instruction`、`action`、`assistantAction`、`orientation`、`roadName` 和该步骤的 `polyline`。坐标顺序固定为经度、纬度，不能混用页面传入的其他坐标系。完整折线或必要导航步骤缺失时不得将快照用于“确实经过”的结论。

`VIA_PLACE` 快照另外保存本次选定的 `via` 地点身份、三类业务类型、可信导航点、有序 `routingWaypoints`、`passageVerification`（只由 Java 校验结果写入的 `PASSED` 和具体证据原因）；普通 `DEFAULT` 快照的 `via` 为 null。默认路线若被验证为某个收费站方案，其 `routeOptionId` 关联记录另存该站点身份和验证结果，仍引用同一条完整路线。该验证结果只对应本次指定的地点，不能代替以后对其他地点的核查。乘客后来问“这条路线经过 X 吗”时，map-service 根据已缓存的同一条路线步骤与新确认的 X 重新计算结论，不重新算一条路线冒充旧路线。`routeOptionId` 是指向候选完整快照的短时选择索引；当前 `routeRef` 由 passenger-api 从本会话最新已持久化卡片取得，并由 map-service 校验；没有独立的 `currentRouteRef` 持久化或缓存字段。以上内部字段不整体发送给模型或客户端，公开 route.card 仍按第 2.2 节输出展示所需字段。

## 7. 临时选择、并发与数据库边界

passenger-service 只使用现有会话表和消息表保存乘客与客服对话、消息顺序、幂等及活动请求；不要求持久化本版路线任务状态。map-service 用带有效期的短时上下文表示待确认地点、待选择地点或路线方案；当前路线由本会话最新已持久化卡片的 `routeRef` 指定，并由地图服务核验。`taskNo`、`placeChoiceId`、`routeOptionId` 和 `expectedRequestVersion` 均按可信乘客、会话和条件版本核对，不能由客户端覆盖地点、路线或验证结论。纯问答无需生成路线任务。

同一会话同一时刻最多一个活动请求；迟到模型或地图结果在写入客服消息前须核对活动请求及 map-service 当前条件版本。成功卡片持久化后，其 `routeRef` 才成为后续“这条路线”的引用；持久化失败的未展示快照不会成为当前路线，按缓存有效期清理。地点、方案和完整路线快照暂定 5 分钟有效；缓存过期后旧选项不可再选，旧路线不可再核查，客服先在原会话重新询问并保存两端确认，再按本轮条件查询或规划。历史消息只显示原有文字、里程和预计时间，完整折线不可再展开。

当前 `passenger_ai_conversation` 已有会话归属、消息序号与活动请求字段，`passenger_ai_message` 已有正文、`payload_json`、幂等键及回复关联字段；消息的乘客归属通过会话表校验，不再在消息表冗余存储 `customer_id`。已有消息表需单独执行 `passenger_ai_message_drop_customer_id_patch.sql`；更新 `CREATE TABLE IF NOT EXISTS` 脚本不会移除旧列。旧 `passenger_ai_route_task` 的 WAITING_ROUTE_SELECTION、SELECTED 和候选快照属于已退出的必选候选流程，不在本期 SQL 中创建，也不作为本版实现前提。落地前仍需核对实际环境的字段和约束；其他明确缺口再针对性迁移。

## 8. 错误契约

建立 SSE 前沿用统一 JSON 错误结构；建立 SSE 后发送 turn.failed，包含 requestNo、稳定 code 和面向乘客的 message。归属不匹配按不存在处理，不泄漏资源是否属于他人。

| 稳定错误码 | 含义 |
| --- | --- |
| AI_CONVERSATION_NOT_FOUND | 会话不存在、已删除或归属不匹配 |
| AI_ROUTE_TASK_NOT_FOUND | 地图服务短时选择任务不存在或归属不匹配 |
| AI_CURRENT_ROUTE_NOT_FOUND | 当前路线引用不存在或归属不匹配 |
| AI_CURRENT_ROUTE_EXPIRED | 当前路线快照已过期；客服须先在原会话重新确认起终点，再生成并展示本轮路线 |
| AI_REQUEST_IN_PROGRESS | 同会话另有活动请求 |
| AI_REQUEST_PROCESSING | 同幂等请求仍执行中 |
| AI_REQUEST_EXPIRED | 活动请求超时后已落库为失败；同键重试重放失败，迟到成功结果不得覆盖 |
| AI_REQUEST_VERSION_CONFLICT | 地图服务短时条件版本变化 |
| AI_PLACE_CHOICE_NOT_FOUND | 地点选项不存在或不属本轮 |
| AI_PLACE_CHOICE_EXPIRED | 地点选项已过期 |
| AI_ROUTE_OPTION_NOT_FOUND | 收费站路线方案不存在或不属本轮 |
| AI_ROUTE_OPTION_EXPIRED | 收费站路线方案已过期 |
| AI_ROUTE_ALREADY_COMPLETED | 不同请求试图替换已完成方案 |
| AI_MESSAGE_TOO_LONG | 用户文本超过上限 |
| AI_POI_NOT_FOUND | 地点查无结果 |
| AI_POI_LOOKUP_FAILED | 地点查询服务暂时失败 |
| AI_ROUTE_UNVERIFIABLE | 不能证明路线实际经过指定地点或动作 |
| AI_ROUTE_NOT_FOUND | 本次条件下未找到可行路线 |
| AI_PROVIDER_TIMEOUT | 模型超时 |
| AI_PROVIDER_UNAVAILABLE | 模型服务暂时不可用 |
| AI_MAP_TIMEOUT | 地图超时 |
| AI_MAP_UNAVAILABLE | 地图算路暂时不可用或返回的数据无效 |
| AI_UPSTREAM_LIMITED | 上游限流 |
| AI_INTERNAL_ERROR | 未分类内部错误 |

缺少起点或终点、地点搜索返回零条候选均属于正常追问，不作为地图失败。模型调用异常属于 AI_PROVIDER_UNAVAILABLE，返回乘客可读的暂时无法处理提示，不伪装成缺少起终点；模型输出无法解析时仍可请乘客重新描述。地图内部 AI 路线接口的业务失败使用真实 HTTP 状态，并通过 `X-Ai-Error-Code` 响应头传递稳定错误码；passenger-api 按错误码形成 `turn.failed`，不依据响应体的数字状态码猜测原因。地图缓存或选项过期可在本轮转成客服重新确认两端的 `answer.completed`，并把提问持久化；上述过期错误码用于内部区分原因，不应让乘客只看到失败代码。当前路线证据不足的 UNVERIFIABLE 和本次查询未找到路线的 NOT_FOUND_IN_QUERY 是业务结论，不得混为 AI_ROUTE_NOT_FOUND。

## 9. 非本期与实施边界

不提供地图页路线导入客服、路线下单、司机导航、实际行驶核验、停车计费或费用透明问答。map-service 缓存基础类与客服默认路线服务已实现；目标接口仍没有 Controller 或 Agent 编排的完整实现，实际 Redis 连接、公开调用链、现有会话与消息表的兼容性及地图证据强度仍需核对。短时层代码审阅后再处理持久化实现和 TEST。
