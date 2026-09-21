# 乘客端 AI 客服：路线核查与指定途经点规划 API

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v2.12 |
| 日期 | 2026-09-21 |
| 状态 | 与 PRD v1.15、TECH v2.13 对齐；AI 客服第一期接口与前后端调用代码开发完成，待环境和端到端验收 |
| 产品范围 | 第一期：默认路线、当前路线核查、指定地点路线卡片和会话管理；后续目标：收费站路线方案查询与选择 |
| 本轮范围 | 第一期交付边界与接口状态对齐，不新增接口 |

## 1. 契约原则

1. 每轮路线问答必须在本客服会话中取得地图身份明确的起点和终点。新会话不继承地图页的起终点、路线或导航状态；缺任一项先追问，地图唯一定位则直接继续，有歧义则返回地点候选。乘客完成最后一个端点选择后立即处理原始意图，不要求再回复“对”；同一会话只沿用仍有效的地图服务临时上下文，缓存失效后重新提供并定位两端。
2. “这条路线是否经过”必须指向本客服会话已展示、仍可从地图服务解析的真实路线。客户端只提交聊天文字，不提交 currentRouteRef、折线、POI ID、导航点或时间作为事实。没有有效客服路线而起终点已由地图确定时，先生成并明确展示本轮地图默认路线，再核查它。
3. 指定一个明确地点后，地图验证成功就直接返回完整 route.card 和这条路线预计时间，不要求乘客提交 routeCandidateId，也不计算与原路线的差值。
4. 开放式收费站方案不纳入第一期，当前不实现：未来仅在乘客主动问“可以经过哪几个收费站”时返回 route.options，再用 routeOptionId 选定方案。当前已实现的地点身份歧义使用 place.choices 和 placeChoiceId 澄清，与目标 routeOptionId 不能混用。
5. 模型负责把当前路线问题归入受控核查含义；地图及服务端确定性校验负责地点、路线关系和分钟数。含义未明确时先让乘客选择“从附近经过”“使用出口或入口”或“实际进入站点”。仅靠近 POI 中心点不能当作使用出口、入口或实际到达；导航步骤缺少匹配动作时返回无法确认，不据此断言未使用。
6. 交付到问答结论或路线卡片和预计时间为止；不启动导航、不下单、不追踪实际出行。

AI 客服第一期已完成起终点提取与地点澄清、单端点续接、默认路线和指定地点路线卡片、当前路线核查、会话创建／列表／历史／请求状态／删除及 SSE 轮次编排的代码开发；是否满足发布条件仍以专项 TEST 的环境与端到端验收为准。开放式收费站方案选择是后续目标契约，不属于第一期未完成接口。历史必选 `routeCandidateId` 候选流程不能当作本版已实现接口。全量 `passenger_schema.sql` 和增量 `passenger_ai_conversation_patch.sql` 已包含这些会话管理所需字段，本轮无需新增表或迁移；旧路线任务表在各环境是否已删除需分别核验。

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
  "via": {"name": "五常收费站"},
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

routeRef 是地图服务生成的随机、不透明路线快照引用，可随新卡片展示，并作为该客服消息的展示摘要保存，但乘客后续请求不回传它。passenger-api 从本会话最新一张已持久化的路线卡片取得当前 `routeRef`，再由 map-service 校验归属、条件版本和有效期；不另存 `currentRouteRef` 状态。消息中的旧 `routeRef` 不能在缓存过期后恢复路线。未来尚未选择的 route.options 也不应改变当前路线；客服卡片不修改地图页路线。

若乘客问“这条路线经过 XX 吗”，但本会话此前没有可核查的路线，服务端在起终点明确后新建默认路线并以 route.card 返回：payload 顶层与 `routeCard` 并列携带 `routeCheck`，包含该卡片的 routeRef、核查地点和 PASSED、NOT_PASSED、UNVERIFIABLE 或 CONFLICT；说明文字以“这是本轮新生成的默认路线。”开头，明确核查对象。地点身份或核查含义仍需澄清时本轮先不下发卡片：返回 place.choices 或 route-check.choices 并携带服务端续接上下文，乘客完成选择后的结论轮次再补发这张带 routeCheck 的 route.card。旧路线快照失效（map-service 返回 CONTEXT_UNAVAILABLE）时同样按“没有可核查路线”处理，新建默认路线后核查它，并说明之前的路线已失效。

### 2.3 核查与选项结果

当前路线核查结果包含已展示卡片的 routeRef、地点摘要、`checkMode` 和 verdict。`checkMode` 只允许 `PASS_NEARBY`、`USE_EXIT`、`ENTER_HIGHWAY`、`VISIT_PLACE`、`UNSPECIFIED`；verdict 只允许 PASSED、NOT_PASSED、UNVERIFIABLE、CONFLICT。PASSED 和 NOT_PASSED 均针对这条具体路线及已确认含义；地图证据或地点身份不足时必须使用 UNVERIFIABLE，不把另一条可达路线当作当前路线。CONFLICT 表示已确认动作与地图地点角色或目标附近的有序路线状态矛盾，响应同时返回稳定 `reason` 和可读 `detail`；当前原因包括 `TARGET_ROLE_CONFLICT`、`ALREADY_ON_HIGHWAY_BEFORE_TARGET`。

给定起终点的“有没有经过 XX 的路线”使用 FOUND、NOT_FOUND_IN_QUERY、UNVERIFIABLE，避免把本次未找到说成所有道路都不存在。该查询已实现为只发送 `answer.completed`：payload 顶层为 `routeExists`，包含地点展示摘要 `place`（`name`，可选 `city`）、`conclusion` 和可选 `detail`；本轮不返回 route.card，也不含 routeRef、折线、坐标或导航步骤。两端或地点未定时先追问，地点有歧义时先返回 place.choices，乘客选择完成后继续本次查询。

追问与选项类客服消息（缺少端点的 answer.completed、place.choices、route-check.choices）的服务端持久化 payload 顶层另存内部续接上下文 `checkContext`，包含 `intent`（当前路线核查为 CURRENT_ROUTE_CHECK，存在性查询为 ROUTE_EXISTS）、地点片段与可选区域、受控 `checkMode`、被核查路线的 `routeRef`、`routeShown` 和 `previousRouteExpired`。它只供服务端在后续轮次恢复同一次核查，不属于客户端契约：实时 SSE 事件、历史消息与请求状态响应一律剥离该字段，剥离后没有其余公开字段时按无载荷返回。已给出终态结论（含存在性结论与选择完成后的结论）的回复不写该字段。

目标中的收费站方案摘要包含 routeOptionId、收费站摘要、durationSeconds、isMapDefault、expiresAt，并在服务端关联完整路线快照；当前尚未实现。已实现的地点澄清摘要包含 placeChoiceId、名称、类型、城市或地址、expiresAt；不发送原始 POI ID、坐标和高德 JSON。当前起终点地点澄清的 `place.choices` 消息使用 `payload.placeChoices`，其中包含 taskNo、role（ORIGIN 或 DESTINATION）、choices、expiresAt、conditionVersion 和 requestVersion；客户端将此 requestVersion 作为选择请求的 expectedRequestVersion。当前路线含义澄清使用 `payload.routeCheckChoices`，包含 `taskNo`、目标地点展示摘要、`choices`（`choiceId`、`label`，其中 `choiceId` 的值是受控 checkMode）、`expiresAt` 和 `requestVersion`；不包含 routeRef、坐标或导航步骤。

起终点 `place.choices` 的字段契约不变；地图服务会先按“高速口／收费站”或宽泛景区名称过滤明显不相关 POI、去重和排序，并将这两类查询的展示选项限制在最多 5 项。若所需地点未展示，乘客可在同一会话中输入更具体名称；接口不因筛选后仅一项就绕过原有唯一性规则自动选择。

## 3. 客服会话内建立路线与当前路线引用

本期不提供地图页路线转交客服的 route-previews 公开接口。当前地图页使用的 /api/v1/map/route 及其页面状态不进入客服。新客服会话从空起终点和空当前路线开始；乘客在聊天文字里给出两端后，地图唯一定位则本轮继续，有歧义时返回带城市或地址的地点候选，不能静默选第一条。只有地图身份明确的起终点才能用于向 map-service 请求驾车路线并展示 route.card；候选选择完成后直接请求，不再追加文字确认。

### 3.1 建立本会话默认路线

乘客发送“帮我规划一条从德清高速路口到西溪湿地的路线”时，客服识别两端并查询地图。两端唯一时，本轮直接让 map-service 获取真实地图路线、生成 routeRef 并缓存完整路线与导航步骤；任一端有歧义时返回带城市或地址的 place.choices，乘客完成最后一个端点选择后在该选择轮次直接获取路线。若本轮只提供一端，map-service 先确认并以乘客、会话、服务端 requestNo 和条件版本短时保存该真实地点，客服明确追问另一端；下一轮补齐后使用新的条件版本确认两端并继续原始默认或指定途经点意图。单端点或选项缓存失效后重新询问，不能从聊天摘要恢复坐标。最终客服路线卡片及展示摘要也作为消息保存；这张已持久化的卡片成为后续“这条路线”的来源。

### 3.2 后续核查与失效

乘客随后问“这条路线经过五常收费站吗”，passenger-api 核对会话归属后，从本会话最新一张已持久化的路线卡片读取 `routeRef`，再由 map-service 解析同一条路线。map-service 核对乘客、会话、条件版本、路线完整性和有效期；不能用重新规划的另一条路线冒充已有路线。引用或上下文失效时旧路线不能继续核查；客服在原会话要求乘客重新说出完整起终点，不能把历史卡片中的可读名称恢复为可信地图地点。旧卡片有指定途经点时还需确认本轮是否继续要求经过该点，不得无声改成默认路线。客服追问及乘客的补充或修正回复都由 passenger-service 保存。map-service 重新查询并核对本轮地点后，才生成并展示新路线进行核查，并明确说明核查对象已更新。完整缓存暂定 5 分钟；历史卡片过期后仅保留文字、里程和预计时间，不再提供完整折线。乘客在新消息中重新提供完整地点且地图唯一确定时，可直接按该消息继续。

## 4. 对话公开 API

### 4.1 会话与历史

当前已实现以下公开路径：POST /app/api/v1/ai/conversations 创建会话；GET /app/api/v1/ai/conversations 查询当前乘客的活动会话列表；GET /app/api/v1/ai/conversations/{conversationNo}/messages 查询历史；GET /app/api/v1/ai/conversations/{conversationNo}/requests/{requestNo} 查询本轮 PROCESSING／COMPLETED／FAILED 状态；DELETE /app/api/v1/ai/conversations/{conversationNo} 逻辑删除会话。所有路径均经过动作权限解析和会话归属校验，跨乘客访问按不存在处理。

创建会话由当前认证乘客和 Idempotency-Key 幂等定位。会话列表按 `lastMessageAt`、主键倒序，使用 `beforeConversationNo` 服务端游标分页，单页 1 至 50 条；标题优先取已保存标题，否则取首条乘客消息，空会话显示“新对话”。请求状态只查询本会话已保存的 requestNo，并返回请求版本、客户端幂等键、状态和可选最终客服消息。删除是幂等逻辑删除，同时清空活动请求；删除后列表、历史、状态及 begin／complete／fail 均不可再访问，迟到结果不得写回。H5 提供会话抽屉、切换和删除，并在 SSE 结果不确定时先查询请求状态，再以历史消息对账。

历史消息中的乘客消息返回原 `clientMessageNo`（该轮客户端 Idempotency-Key），供断流后与本地待发送消息精确合并；客服消息的该字段为 null。历史 route.card 持久保存说明文字、起终点和途经点名称、里程、预计时间及失效时间；完整折线只在地图服务缓存仍有效时可按归属临时读取。过期后历史卡片不展开完整折线。未来 route.options 只持久化文字和选项摘要，过期后不可再选。

会话列表请求使用 `limit` 和可选 `beforeConversationNo`；响应示意：

~~~json
{"conversations":[{"conversationNo":"AIC-example","title":"从德清到宁波站","status":"ACTIVE","lastMessageAt":"2026-09-21T09:30:00","createdAt":"2026-09-21T09:20:00"}],"hasMore":false,"nextBeforeConversationNo":null}
~~~

请求状态响应包含 `requestNo`、`requestVersion`、`clientMessageNo`、`status` 和可空的 `assistantReply`。PROCESSING 时 `assistantReply=null`；COMPLETED 或 FAILED 时返回已落库终态，不重新执行模型或地图。删除响应包含 `conversationNo` 与 `deletedAt`；对同一乘客重复删除返回同一逻辑删除结果。

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
      "via": {"name": "五常收费站"},
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

content 去除首尾空白后长度为 1 至 1000。首次给出完整起终点时，地图唯一定位便在本轮直接执行原始意图；任一端匹配多个地图地点时返回包含城市、地址的 place.choices，不能静默选第一条。乘客完成最后一个端点选择后，在该选择轮次直接继续原意图，不再发“对吗”确认问题。地图临时上下文、路线或选项缓存失效时，在同一对话中要求重新提供两端并持久化追问；乘客回复也持久化，随后 map-service 重新查询地图地点。单独回复“对”不能恢复已失效的地点。文字请求体只接收 content，不接收 pageContext、起终点结构、currentRouteRef、POI ID、导航点、折线、路线时长、routeOptionId 或任务状态。

起终点齐全且地图唯一定位时可在本轮产生路线业务事件；存在歧义时先返回 place.choices，最后一个端点选择完成后直接产生对应业务结果：

| 意图 | 最终事件 | 主要结果 |
| --- | --- | --- |
| 起点或终点缺失 | answer.completed | 追问缺失端点；补齐并确定地图身份后直接继续原意图 |
| 地图临时上下文或路线缓存失效 | answer.completed | 要求重新提供起终点；客服追问和乘客后续回复均进入原会话历史，不凭旧卡片核查 |
| 核查本会话当前路线是否经过 | 含义不明确时为 route-check.choices；核查完成为 answer.completed；本会话尚无路线时可由同轮 route.card 携带 routeCheck | 已展示路线、具体地点和已确认 checkMode 下的 PASSED、NOT_PASSED、UNVERIFIABLE、CONFLICT |
| 查询有无经过路线 | answer.completed | `payload.routeExists` 给出 FOUND、NOT_FOUND_IN_QUERY、UNVERIFIABLE，本轮不出路线卡 |
| 普通起终点规划 | route.card | 客服本轮生成的地图默认路线和预计时间 |
| 指定地点规划成功 | route.card | 一条已验证的新路线及自身预计时间 |
| 指定地点身份不明确 | place.choices | 有效地点选项 |
| 查询可选收费站路线（目标契约，当前未实现） | route.options | 本轮已验证的不同收费站方案 |

“这条路线”没有有效本会话路线、但本轮起终点地图身份已确定且 map-service 临时上下文有效时，服务端可先生成并展示本轮默认路线，同时在 route.card 的说明中写明核查对象是这条新生成的路线。若旧路线或上下文已失效，先重新提供并定位两端；无法建立真实路线时返回明确失败或追问。

首轮直接处理也按意图区分：`从 A 到 B，必须经过 X，帮我规划` 在 X 唯一明确且路线通过验证后返回 route.card，不先生成默认路线；`从 A 到 B，这条路线经过 X 吗` 在本会话没有路线时先生成并展示默认路线，route.card 同时带该路线的 routeCheck；`从 A 到 B，有没有经过 X 的路线` 只返回 answer.completed 的存在性结论。若 X 指向多个地点，先返回地点澄清，不把地点搜索结果当作经过证据。

### 4.3 选择地点

POST /app/api/v1/ai/conversations/{conversationNo}/route-tasks/{taskNo}/place-choice/stream

请求体示例：

~~~json
{"expectedRequestVersion": 5, "placeChoiceId": "PC-example", "choiceName": "三墩收费站", "choiceCity": "杭州"}
~~~

`choiceName` 和可选的 `choiceCity` 必须来自上一条 `place.choices` 的展示摘要，只用于把乘客选择保存成可读的聊天正文；地图业务决策只使用 `placeChoiceId`。`taskNo`、地点选项及成员关系由 map-service 短时缓存，不要求 passenger-service 保存路线任务。passenger-api 先核对乘客和会话归属，map-service 再核对条件版本、有效期及选项成员关系，从可信地点快照继续原先被暂停的意图。乘客选择与客服结果都作为消息经 passenger-service 持久化，正文不得展示 `PC-...` 内部编号。若原意图是指定地点规划，验证成功返回 route.card；若原意图是核查当前路线，已有有效路线时返回 answer.completed，没有有效路线但两端仍有效时先生成并展示默认路线，再以带 routeCheck 的 route.card 返回；若原意图是查询有无经过路线，则返回 answer.completed。此动作可能调用地图，沿用消息 begin、外部调用、complete 两段短事务；选项或上下文过期时要求重新提供并定位两端。客户端不得提交 POI ID、坐标或入口出口。

当前默认路线起终点流程中，两端都有歧义时先选择起点，再选择终点；两端身份确定后立即生成路线卡，不再复述完整起终点或等待文字确认。已选地点从 map-service 的选项快照恢复，不再次让模型猜测同名地点。

### 4.4 选择当前路线核查含义

POST /app/api/v1/ai/conversations/{conversationNo}/route-check-tasks/{taskNo}/mode-choice/stream

请求头包含 `Idempotency-Key` 和 `Accept: text/event-stream`。请求体示例：

~~~json
{"expectedRequestVersion": 6, "checkMode": "USE_EXIT", "choiceLabel": "从这里驶出高速"}
~~~

`taskNo` 必须是上一条 `route-check.choices` 中的 `RCT-...`，`checkMode` 必须取自该消息某个选项的 `choiceId`，`choiceLabel` 必须与同一选项的 `label` 一致。passenger-api 保存自然语言乘客消息“我想确认：从这里驶出高速”，再从本会话最新路线卡取得 routeRef；map-service 按可信乘客、会话、条件版本、请求版本、任务成员和有效期恢复同一个地点与核查含义，重新校验当前路线后返回结论。客户端不能提交或替换 routeRef、地点、坐标和导航证据。

### 4.5 选择收费站路线方案（目标契约，尚未接入）

POST /app/api/v1/ai/conversations/{conversationNo}/route-tasks/{taskNo}/route-option/selection

请求头包含 Idempotency-Key 和 Accept: text/event-stream。请求体：

~~~json
{"expectedRequestVersion": 2, "routeOptionId": "RO-example"}
~~~

本节保留后续目标契约，当前 passenger-api 与 map-service 均未提供该选择端点，`PassengerRouteOptionCache` 也未装配为生产 Bean。未来接入后，只对 map-service 短时缓存中仍有效的路线方案选择生效：passenger-api 先核对会话归属，在数据库事务外让 map-service 按可信乘客、会话、条件版本、taskNo 和 routeOptionId 核对选项成员与有效期，并读取同一份完整路线快照；passenger-service 仅按会话消息的既有幂等和顺序规则保存乘客选择及客服卡片。成功后发送 route.card，不重新调用模型或高德算路。客户端不能提交 POI、折线、距离、时间或是否默认等字段。

指定地点规划分支不调用此接口，也不要求选择 routeOptionId。旧 routeCandidateId 不是本版所有规划的必填参数。

## 5. SSE 事件

所有流式接口采用 text/event-stream;charset=UTF-8；SSE 连接上限为 90 秒，每 15 秒发送一次 ping。受理后先发送 turn.started，可发送 turn.status 和必要的 answer.delta，最终在结果成功持久化后发送一个完整内容事件，随后 turn.completed 并关闭流。模型未拿到地图验证结果前，answer.delta 不得提前声称已经过或报出预计分钟数。当前断线恢复先按已知 requestNo 查询请求状态，再读取历史消息合并最终文字、里程和时间；若断线早于 turn.started，则继续用历史乘客消息的 clientMessageNo 找回 requestNo。完整折线只在地图缓存有效时重新读取，不依赖半截路线 JSON。

| 事件 | 用途 |
| --- | --- |
| turn.started | 请求号、已保存用户消息和当前版本 |
| turn.status | 受控业务阶段，不输出模型思维过程 |
| answer.delta | 仅已安全确定的自然语言增量 |
| answer.completed | 无卡片的完整问答、必要追问或无法验证说明 |
| place.choices | 完整地点澄清选项 |
| route-check.choices | 当前路线地点已确定但“经过”的具体含义仍需乘客选择 |
| route.options | 目标事件：完整收费站方案摘要集合，当前未实现 |
| route.card | 一张完整默认路线或经指定地点路线卡片，含预计时间 |
| turn.completed | 本轮成功结束 |
| turn.failed | 稳定错误码和可读说明 |
| ping | 心跳，不改变业务状态 |

内容事件的 assistantMessage 包含 messageNo、requestNo、role、messageType、content、payload、createdAt；route.card、route.options、place.choices、route-check.choices 的 payload 分别按第 2 节类型给出。相同幂等请求完成后的重放不重新调用模型或高德；服务端只在地图快照仍有效时补出完整路线，否则返回已持久化的文字和里程、时间摘要及失效标记，再发送 turn.completed。若原请求已落库为失败，同一幂等键只重放原错误码和可读说明的 turn.failed，不发送成功内容事件或 turn.completed。

起终点地图身份确定后不产生单独确认事件：唯一定位时当前文字轮次直接返回业务结果；存在歧义时返回 `place.choices`，乘客完成最后一个端点选择后，该选择轮次直接返回业务结果。

核查已有客服路线的最终事件示意，经过结论只对本会话当前路线生效；`routeCheck` 含被核查路线的 `routeRef`、内部 `status`、`verdict` 和目标地点 `target` 展示摘要（`name`、`displayType`、可选 `city`／`address`、`businessType`），不含坐标、折线或导航步骤：

~~~text
event: answer.completed
data: {"requestNo":"AIR-example","assistantMessage":{"messageType":"TEXT","content":"当前这条路线不经过五常收费站。","payload":{"routeCheck":{"routeRef":"RR-example","status":"CHECKED","verdict":"NOT_PASSED","target":{"name":"五常收费站","displayType":"收费站","city":"杭州市","businessType":"TOLL_STATION"}}}}}
~~~

本会话没有可核查路线但本轮两端已确定时，passenger-api 先生成并缓存本轮默认路线，再核查它；最终以同轮 `route.card` 返回，payload 顶层与 `routeCard` 并列携带 `routeCheck`，说明文字以“这是本轮新生成的默认路线。”开头。地点身份或核查含义仍需澄清时本轮不发卡片，先返回携带续接上下文的 `place.choices` 或 `route-check.choices`，乘客完成选择的结论轮次再补发该卡片：

~~~text
event: route.card
data: {"requestNo":"AIR-example-1b","assistantMessage":{"messageType":"ROUTE_CARD","content":"这是本轮新生成的默认路线。当前这条路线不经过五常收费站。","payload":{"routeCard":{"routeRef":"RR-example-1b","origin":{"name":"德清高速路口"},"destination":{"name":"西溪湿地"},"via":null,"route":{"coordinateSystem":"GCJ02","distanceMeters":45000,"durationSeconds":3200,"polyline":[]},"generatedAt":"2026-09-17T10:00:00+08:00","expiresAt":"2026-09-17T10:05:00+08:00"},"routeCheck":{"routeRef":"RR-example-1b","status":"CHECKED","verdict":"NOT_PASSED","target":{"name":"五常收费站","displayType":"收费站","businessType":"TOLL_STATION"}}}}}
~~~

查询“有没有经过某地点的路线”的最终事件示意；本轮只给存在性结论，不返回路线卡：

~~~text
event: answer.completed
data: {"requestNo":"AIR-example-4","assistantMessage":{"messageType":"TEXT","content":"找到一条从当前起点到终点、经过「五常收费站」的路线。","payload":{"routeExists":{"place":{"name":"五常收费站","city":"杭州"},"conclusion":"FOUND"}}}}
~~~

指定地点规划成功的最终事件示意；routeCard 使用第 2.2 节的完整结构，durationSeconds 是该新路线自身的时间：

~~~text
event: route.card
data: {"requestNo":"AIR-example-2","assistantMessage":{"messageType":"ROUTE_CARD","content":"已找到经过五常收费站的路线，预计行驶55分钟。","payload":{"routeCard":{"routeRef":"RR-example-2","origin":{"name":"德清高速路口"},"destination":{"name":"西溪湿地"},"via":{"name":"五常收费站"},"route":{"coordinateSystem":"GCJ02","distanceMeters":45600,"durationSeconds":3300,"polyline":[]},"generatedAt":"2026-09-17T10:00:00+08:00","expiresAt":"2026-09-17T10:05:00+08:00"}}}}
~~~

收费站方案查询的目标事件示意，当前未实现。此处仅发送摘要；未来每个 routeOptionId 在服务端绑定完整已验证路线：

~~~text
event: route.options
data: {"requestNo":"AIR-example-3","assistantMessage":{"messageType":"ROUTE_OPTIONS","content":"本次找到两种可行方案，请选择一条。","payload":{"taskNo":"AIT-example","requestVersion":2,"expiresAt":"2026-09-17T10:05:00+08:00","routeOptions":[{"routeOptionId":"RO-1","via":{"name":"三墩收费站","businessType":"TOLL_STATION"},"durationSeconds":3000,"isMapDefault":true},{"routeOptionId":"RO-2","via":{"name":"五常收费站","businessType":"TOLL_STATION"},"durationSeconds":3300,"isMapDefault":false}]}}}
~~~

上述地点和分钟数均为接口格式示例，不是地图事实；route.card 示例中的空折线仅为省略，真实成功卡片必须携带完整可展示路线。选定 RO-2 后返回 route.card，而不是另发一个要求乘客再次选择的候选事件。

## 6. 内部 API 与可信上下文

### 6.1 passenger-service

当前 passenger-service 内部能力包含会话创建、列表、历史、请求状态、逻辑删除及以下围绕对话轮次的短事务接口：

- POST /api/v1/internal/ai/conversations：幂等创建当前乘客的客服会话。
- GET /api/v1/internal/ai/conversations：按当前乘客和服务端游标分页读取未删除会话。
- GET /api/v1/internal/ai/conversations/{conversationNo}/messages：核对乘客和会话归属后读取分页历史。
- GET /api/v1/internal/ai/conversations/{conversationNo}/requests/{requestNo}：返回 PROCESSING 或已落库的 COMPLETED／FAILED 最终状态。
- DELETE /api/v1/internal/ai/conversations/{conversationNo}：在会话行锁内幂等逻辑删除并撤销活动请求。

- POST /api/v1/internal/ai/conversations/{conversationNo}/turns/begin：在会话行锁内核对 customerId、会话归属、幂等键和单会话活动请求，同键并发仅一轮可以开始；按顺序保存本轮乘客消息，返回 requestNo、消息序号、有限历史及最新客服消息。活动请求超过接管阈值时先保存旧轮次失败消息，同键重试重放该失败，不再次执行模型或地图；不同键可开启新轮次，旧结果不能迟到覆盖。点击地点或路线方案的选择也应保存为乘客在该对话中的选择消息。
- POST /api/v1/internal/ai/conversations/{conversationNo}/turns/{requestNo}/complete：再次核对归属、活动请求、幂等与会话未删除状态；获取会话行锁后重查同一请求的客服消息，命中则重放已保存结果，否则按顺序保存本轮客服消息并释放活动请求。客服追问、地点候选、路线方案、核查结论和路线卡片都属于客服消息；卡片 `payload_json` 仅含展示摘要及 `routeRef`，不保存完整折线、导航步骤或权威地点身份。成功持久化后才发送对应的最终 SSE 事件。
- POST /api/v1/internal/ai/conversations/{conversationNo}/turns/{requestNo}/fail：获取会话行锁后同样重查同一请求的客服消息；已有结果则重放，否则保存客服可读的失败说明或稳定错误消息并释放活动请求。若地图缓存已写入但消息未完成，未引用的快照按短时有效期清理。

passenger-service 不新增已确认地点、待恢复意图、当前路线引用或路线任务的持久化业务状态。它保存乘客与客服实际发生的完整对话，利用现有会话和消息字段实现顺序、幂等、归属与删除。map-service 的地点、选项和路线缓存失效后，客服要求重新提供起终点的追问与乘客回复也通过上述消息轮次写入同一会话。最新卡片消息的 `routeRef` 可由服务端取出并交给 map-service 核验；过期消息中的地点名称和引用只能用于展示或组织重新提供地点的话术，不能充当地图地点或路线仍有效的证据。

### 6.2 map-service 路线能力

当前没有 `/api/v1/internal/route-agent/stream` 统一 Agent 流式入口，map-service 也不直接输出 `agent.status`、`agent.delta` 或 `agent.result`。passenger-api 通过内部客户端按轮次状态调用下列结构化端点，再由 BFF 统一编排对话消息与公开 SSE：

| 内部路径 | 当前职责 |
| --- | --- |
| POST /api/v1/internal/ai/route/extract | 使用模型提取起终点、指定地点、路线核查意图与受控 checkMode |
| POST /api/v1/internal/ai/route/partial | 解析并短时保存首轮唯一单端点；重名时返回 place.choices |
| POST /api/v1/internal/ai/route/partial/complete | 按上一条服务端 requestNo 和旧条件版本恢复单端点，以本轮新版本补齐另一端 |
| POST /api/v1/internal/ai/route/prepare | 查询并固化起终点地图身份，必要时返回 place.choices |
| POST /api/v1/internal/ai/route/confirm | 兼容保留的端点确认入口；当前唯一地点和最后一个选择均直接继续原意图 |
| POST /api/v1/internal/ai/route/place-choice | 恢复服务端起点或终点选项快照，并继续原始意图 |
| POST /api/v1/internal/ai/route/default | 按已确认起终点生成并缓存默认路线卡片 |
| POST /api/v1/internal/ai/route/via | 基于当前有效路线或已确认起终点，规划并验证指定地点路线；存在性查询复用同一能力，只取结论不出卡 |
| POST /api/v1/internal/ai/route/via/place-choice | 恢复指定地点选项快照并继续规划同一途经要求 |
| POST /api/v1/internal/ai/route/replay | 按可信乘客、会话和 routeRef 重验有效快照；失效时不重新算路 |
| POST /api/v1/internal/ai/route/current-check | 在同一条有效客服路线上启动地点与经过含义核查 |
| POST /api/v1/internal/ai/route/current-check/place-choice | 恢复当前路线核查的地点澄清选择 |
| POST /api/v1/internal/ai/route/current-check/mode-choice | 恢复当前路线核查的受控含义选择 |

map-service 从自身仍有效的临时会话上下文取得已确认的地图地点和条件版本。待选地点不能用于驾车算路；缓存失效时只返回需要重新提供两端的结构化结果，不从消息文字或模型摘要恢复旧坐标、旧路线。完整路线、地点和验证结论由 Java 确定性业务写入结构化响应，不接受客户端或模型虚构的地图事实。目标中的开放式收费站方案与 routeOptionId 解析尚未接入。

当前路线核查的提取响应包含 `intent`、`checkPlaceName`、可选的 `checkPlaceRegion` 和受控 `checkMode`，不包含模型判定的 `checkPlaceBusinessType`。内部 `current-check` 请求传递 `checkMode`，map-service 对未知值按 `UNSPECIFIED` 处理；它仍按地点原文无类型搜索高德，再以 POI `typecode` 识别受支持类型。地图规范名称与乘客原词不同或存在多个候选时返回 `place.choices`，每个选项必须带完整规范名称、展示类型、城市或地址。地点确定而 `checkMode=UNSPECIFIED` 时返回核查含义选项；内部 `/api/v1/internal/ai/route/current-check/mode-choice` 只恢复服务端缓存的路线、地点与允许含义。

存在性查询的提取响应以 `intent=ROUTE_EXISTS` 携带 `checkPlaceName` 和可选的 `checkPlaceRegion`，不携带 `checkMode`；地点缺失时 BFF 先追问地点，不调用地图。两端与地点确定后复用途经点算路能力：本会话没有可核查路线时以 `routeRef=null`、`conditionVersion=requestVersion=本轮条件版本` 调用 `/prepare` 和 `/via`，已有有效路线时按该 `routeRef` 核查。BFF 只映射结论：`ROUTE_READY` → FOUND，`ROUTE_NOT_FOUND` → NOT_FOUND_IN_QUERY，`PLACE_NOT_FOUND`、`PLACE_UNVERIFIABLE` → UNVERIFIABLE，`NEEDS_PLACE_CHOICE` → 带 `flow=ROUTE_EXISTS` 的 place.choices 并继续同一查询，`CONTEXT_UNAVAILABLE` → 要求重新提供两端。该查询不生成或展示路线卡，也不把新算路线写为当前路线。

指定地点规划的提取响应以 `intent=VIA_ROUTE_PLAN` 携带 `viaPlaceName` 和可选的 `viaPlaceRegion`。已有路线时 passenger-api 只从本会话最新已持久化路线卡取得 `routeRef`；首轮同时给出起点、终点和途经点时，先通过 `prepare` 固化两端，再按 `conditionVersion` 调用 `via`。途经地点有歧义时只返回不透明 `placeChoiceId`；`via/place-choice` 从 map-service 短时缓存恢复 POI 身份、业务类型和导航点。`ROUTE_NOT_FOUND`、`PLACE_UNVERIFIABLE` 与地图服务故障分别处理，不能把无路线或证据不足统一报成上游不可用。

map-service 已按可信乘客 ID、会话编号短时保存已核对两端、单端点、待选地点、待选核查含义和完整路线；按 routeRef 和条件版本读取同一条路线以核查地点或重建有效期内的卡片。单端点额外绑定服务端追问 requestNo、已知角色、原始意图与失效时间，不把坐标写入客服消息。Redis 依赖与短时缓存基础类已接入，运行环境连接配置仍待联调核验。完整路线与相关临时上下文暂定 5 分钟有效，缓存丢失等同失效；地图服务不保存聊天历史，passenger-service 不持久化完整路线或地图临时上下文。

map-service 内部完整快照的基础字段为：`routeRef`、`customerId`、`conversationNo`、`conditionVersion`；服务端确认的 `origin`、`destination`（名称、地址或城市、可取得的地图地点 ID、GCJ02 坐标）；`provider`、`coordinateSystem=GCJ02`、`travelMode=DRIVING`、`routeKind=DEFAULT|VIA_PLACE`；`distanceMeters`、`durationSeconds`、按行驶顺序排列的完整 `polyline`、`navigationSteps`；以及 `generatedAt`、`expiresAt`。每个导航步骤保留 `instruction`、`action`、`assistantAction`、`orientation`、`roadName` 和该步骤的 `polyline`。坐标顺序固定为经度、纬度，不能混用页面传入的其他坐标系。完整折线或必要导航步骤缺失时不得将快照用于“确实经过”的结论。

`VIA_PLACE` 快照另外保存本次选定的 `via` 地点身份、三类业务类型、可信导航点、有序 `routingWaypoints`、`passageVerification`（只由 Java 校验结果写入的 `PASSED` 和具体证据原因）；普通 `DEFAULT` 快照的 `via` 为 null。该验证结果只对应本次指定的地点，不能代替以后对其他地点的核查。乘客后来问“这条路线经过 X 吗”时，map-service 根据已缓存的同一条路线步骤与新确认的 X 重新计算结论，不重新算一条路线冒充旧路线。当前 `routeRef` 由 passenger-api 从本会话最新已持久化卡片取得，并由 map-service 校验；没有独立的 `currentRouteRef` 持久化或缓存字段。未来实现 route.options 时，`routeOptionId` 才作为指向候选完整快照的短时选择索引。以上内部字段不整体发送给模型或客户端；公开 route.card 的 `via` 当前只输出名称，业务类型和验证证据仍留在 map-service 权威快照中。

## 7. 临时选择、并发与数据库边界

passenger-service 只使用现有会话表和消息表保存乘客与客服对话、消息顺序、幂等及活动请求；不要求持久化本版路线任务状态。map-service 当前用带有效期的短时上下文表示待选择地点和待选择核查含义；当前路线由本会话最新已持久化卡片的 `routeRef` 指定，并由地图服务核验。`taskNo`、`placeChoiceId`、`checkMode` 和 `expectedRequestVersion` 按可信乘客、会话和条件版本核对，不能由客户端覆盖地点、路线或验证结论。目标 routeOptionId 未接入生产流程。纯问答无需生成路线任务。

同一会话同一时刻最多一个活动请求；迟到模型或地图结果在写入客服消息前须核对活动请求及 map-service 当前条件版本。成功卡片持久化后，其 `routeRef` 才成为后续“这条路线”的引用；持久化失败的未展示快照不会成为当前路线，按缓存有效期清理。地点、方案和完整路线快照暂定 5 分钟有效；缓存过期后旧选项不可再选，旧路线不可再核查，客服先在原会话重新询问并保存两端确认，再按本轮条件查询或规划。历史消息只显示原有文字、里程和预计时间，完整折线不可再展开。

当前 `passenger_ai_conversation` 已有会话归属、消息序号与活动请求字段，`passenger_ai_message` 已有正文、`payload_json`、幂等键及回复关联字段；消息的乘客归属通过会话表校验，不再在消息表冗余存储 `customer_id`。已有消息表需单独执行 `passenger_ai_message_drop_customer_id_patch.sql`；更新 `CREATE TABLE IF NOT EXISTS` 脚本不会移除旧列。旧 `passenger_ai_route_task` 的 WAITING_ROUTE_SELECTION、SELECTED 和候选快照属于已退出的必选候选流程，不在本期 SQL 中创建，也不作为本版实现前提。落地前仍需核对实际环境的字段和约束；其他明确缺口再针对性迁移。

## 8. 错误契约

建立 SSE 前沿用统一 JSON 错误结构；建立 SSE 后发送 turn.failed，包含 requestNo、稳定 code 和面向乘客的 message。归属不匹配按不存在处理，不泄漏资源是否属于他人。下表只列当前代码可能发出或重放的 `turn.failed` code：

| 稳定错误码 | 含义 |
| --- | --- |
| AI_CONVERSATION_NOT_FOUND | 会话不存在或归属不匹配 |
| AI_REQUEST_IN_PROGRESS | 同会话另有活动请求 |
| AI_REQUEST_PROCESSING | 同幂等请求仍执行中 |
| AI_REQUEST_EXPIRED | 活动请求超时后已落库为失败；同键重试重放失败，迟到成功结果不得覆盖 |
| AI_IDEMPOTENCY_CONFLICT | 同一幂等键对应的请求内容不一致 |
| AI_POI_LOOKUP_FAILED | 地点查询服务暂时失败 |
| AI_ROUTE_NOT_FOUND | 本次条件下未找到可行路线 |
| AI_ROUTE_UNVERIFIABLE | 已找到驾车路线，但无法证实所选收费站起终点的通行方向；不生成成功路线卡 |
| AI_PROVIDER_TIMEOUT | 模型超时 |
| AI_PROVIDER_UNAVAILABLE | 模型服务暂时不可用 |
| AI_PROVIDER_BUSY | 模型调用有界执行器已满，稍后重试 |
| AI_PROVIDER_INVALID_RESPONSE | 模型返回空结果或缺少可用文本 |
| AI_MAP_TIMEOUT | 地图超时 |
| AI_MAP_UNAVAILABLE | 地图算路暂时不可用或返回的数据无效 |
| AI_INTERNAL_AUTH_FAILED | passenger-api 与 map-service 内部 token 缺失或不匹配 |
| AI_STREAM_TIMEOUT | SSE 轮次超过连接时限，客户端应重新发送或读取历史 |
| AI_SERVER_BUSY | passenger-api 轮次执行器已满，请稍后重试 |
| AI_INTERNAL_ERROR | 未分类内部错误 |

缺少起点或终点、地点搜索返回零条候选、地图上下文或地点／核查含义选项过期，均按当前编排转为正常追问或 `CONTEXT_UNAVAILABLE`，不向公开流发送历史设计中的 `AI_POI_NOT_FOUND`、`AI_CURRENT_ROUTE_EXPIRED`、`AI_PLACE_CHOICE_EXPIRED` 或 `AI_REQUEST_VERSION_CONFLICT`。模型调用异常属于 AI_PROVIDER_UNAVAILABLE，返回乘客可读的暂时无法处理提示，不伪装成缺少起终点；模型输出无法解析时仍可请乘客重新描述。地图内部 AI 路线接口的业务失败使用真实 HTTP 状态，并通过 `X-Ai-Error-Code` 响应头传递稳定错误码；passenger-api 按已支持的错误码形成 `turn.failed`，不依据响应体的数字状态码猜测原因。其中 `AI_ROUTE_CONTEXT_UNAVAILABLE` 是当前 map-service 内部代码，BFF 会将它消化为追问而不原样发给公开流；`AI_INVALID_REQUEST` 是 map-service 内部请求校验码，表明 BFF 调用契约错误，也不是当前公开 `turn.failed` 代码。当前路线证据不足的 UNVERIFIABLE 和本次查询未找到路线的 NOT_FOUND_IN_QUERY 是业务结论，不得混为 AI_ROUTE_NOT_FOUND。

`AI_ROUTE_OPTION_NOT_FOUND` 和 `AI_ROUTE_OPTION_EXPIRED` 只是开放式收费站方案的目标错误码；当前未实现 route.options 与 routeOptionId 选择，不得宣称生产链路已会返回这两个 code。

## 9. 非本期与实施边界

不提供地图页路线导入客服、路线下单、司机导航、实际行驶核验、停车计费或费用透明问答。当前已实现 map-service 路线条件／快照／单端点／地点与核查含义缓存、默认及指定地点路线、当前路线核查，passenger-service 会话和消息持久化及管理，passenger-api 公开 SSE 编排，以及乘客 H5 的会话列表、历史、删除、断流状态恢复、地点选择、核查含义选择和路线卡展示。仍未实现开放式收费站多方案；运行环境 Redis、内部 token、模型密钥、现有数据库迁移以及真实地图证据强度仍需验收。专项 TEST 记录自动化结果与待执行的人工场景，不等同生产验收。
