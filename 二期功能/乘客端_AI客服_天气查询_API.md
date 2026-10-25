# 乘客端 AI 客服：天气查询 skill API

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v1.0 |
| 日期 | 2026-10-25 |
| 状态 | 第一版接口开发完成，待运行环境与真实页面验收 |
| 产品依据 | 乘客端_AI客服_天气查询_PRD.md |
| 复用契约 | 乘客端_AI客服_指定途经点路线规划_API.md 的会话与 SSE 轮次 |

## 1. 公开调用

天气查询不新增公开接口，复用现有文字 SSE：

```
POST /app/api/v1/ai/conversations/{conversationNo}/messages/stream
Authorization: Bearer <token>
Idempotency-Key: <key>
Accept: text/event-stream
Content-Type: application/json

{"content":"杭州明天天气"}
```

服务端识别 `WEATHER_QUERY` 后，直接返回 `answer.completed`，不产生 `route.card`、`place.choices` 或 `route-check.choices`。成功结果仍按既有轮次先落库、再发送 SSE 内容事件，最后 `turn.completed`。

### 1.1 成功结果

~~~text
event: answer.completed
data: {"requestNo":"AIR-weather-1","assistantMessage":{"messageType":"TEXT","content":"杭州明天（10月26日，星期一）白天多云，夜间小雨，气温 18~24℃，东北风 1-3级。夜间有雨，出门记得带伞。","payload":{"weather":{"location":{"name":"杭州市","province":"浙江省","city":"杭州市","district":"西湖区","adcode":"330100"},"date":"2026-10-26","week":"星期一","dayWeather":"多云","nightWeather":"小雨","dayTemp":24,"nightTemp":18,"windDirection":"东北风","windPower":"1-3级","source":"amap"}}}}
~~~

`weather` 字段：

| 字段 | 说明 |
| --- | --- |
| `location.name` | 展示用地点名称；优先使用乘客输入的城市文本组织正文，payload 保留地图规范城市名 |
| `location.province` / `city` / `district` | 高德返回的省、市、区县，可空 |
| `location.adcode` | 高德城市编码，仅用于服务端解析和排障，不要求客户端使用 |
| `date` | 预报日期，`yyyy-MM-dd` |
| `week` | 星期文本；数字 `1`～`7` 会转成“星期一”～“星期日” |
| `dayWeather` / `nightWeather` | 白天/夜间天气现象 |
| `dayTemp` / `nightTemp` | 白天最高/夜间最低温度，摄氏度 |
| `windDirection` / `windPower` | 风向、风力；缺失“风/级”单位时由服务端补齐，如“东北”→“东北风”、“1-3”→“1-3级” |
| `source` | 固定 `amap` |

缺失温度或天气现象时，对应字段可省略；正文显示“气温暂缺”或“天气信息暂缺”。

### 1.2 追问与失败

| 情况 | 事件 | 内容 |
| --- | --- | --- |
| 未提供城市 | `answer.completed` | “请告诉我城市和日期，例如：杭州明天天气。” |
| 日期超出今天到后天、大后天 | `answer.completed` | “目前只能查今天、明天、后天、大后天这四天的预报，请换个日期。” |
| 城市不存在 | `answer.completed` | “没有找到「X」这个城市，请补充省份或换一个名称。” |
| 高德超时、无权限、限流或返回异常 | `turn.failed` | `AI_WEATHER_UNAVAILABLE`，“天气服务暂时不可用，请稍后重试” |

城市缺失、日期超出范围和城市不存在都是正常问答结果，不使用 `turn.failed`。

### 1.3 上下文城市复用

- 本轮文字有显式城市时，始终使用显式城市。
- 本轮没有城市，且同一会话上一轮是已持久化的天气回答时，passenger-api 从该轮 `payload.weather.location` 恢复城市，并在正文前加“按你刚才说的杭州查：”。
- 只读取上一轮天气 payload；不读取完整聊天历史、不跨会话、不使用 GPS、地图页或当前路线城市。
- 显式城市覆盖复用结果；没有可复用天气上下文时仍返回请补充城市的追问。
- 示例：`杭州明天天气` → `后天呢` → 复用杭州；改说 `上海呢` 时使用上海。

### 1.4 待补城市的续接

- 客服问“请告诉我城市”时，passenger-api 在持久化 payload 顶层保存私有 `weatherContext`（原问题的 `dayOffset`）；实时 SSE、历史消息和请求状态一律剥离该字段。
- 下一轮乘客只回一个城市（如 `德清`）时，passenger-api 读取 `weatherContext`，用该城市和原日期继续天气，不再把 `德清` 当作路线起点。
- 若乘客改为明确的路线动作或给出两端（如 `从德清到杭州`），路线意图优先，`weatherContext` 不再生效。
- 示例：`后天会下雨吗` → `请告诉我城市，例如：杭州后天天气。` → `德清` → `德清后天…`。

## 2. 意图与提取字段

`POST /api/v1/internal/ai/route/extract` 的响应新增以下字段，仅 `intent=WEATHER_QUERY` 时非空：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `weatherPlaceName` | string/null | 乘客原话中的城市或区县片段，不补全、不改写 |
| `weatherPlaceRegion` | string/null | 乘客明确提到的省份/城市/行政区 |
| `weatherDayOffset` | integer/null | 今天=0、明天=1、后天=2、大后天=3；未指定日期为 null；明确超出四天为 -1 |

非天气意图的三个字段均为 null。旧客户端忽略新增字段即可。

内部 extract 请求可带 `weatherContinuation=true`，仅表示上一轮是天气回答，用于让“明天呢/那里呢”归为 `WEATHER_QUERY`；它不携带聊天正文，城市仍由 passenger-api 从上一轮 payload 恢复。

意图优先级：消息同时包含路线起终点或路线规划/核查动作时，仍返回对应路线意图；只有天气是唯一诉求时才返回 `WEATHER_QUERY`。

## 3. 内部接口

```
POST /api/v1/internal/ai/weather/forecast
X-Internal-Service-Token: <MAP_INTERNAL_TOKEN>
Content-Type: application/json
```

请求：

~~~json
{"requestNo":"REQ-1","placeName":"杭州","placeRegion":null,"dayOffset":1}
~~~

响应：

~~~json
{
  "status": "OK",
  "location": {"name":"杭州市","province":"浙江省","city":"杭州市","district":"西湖区","adcode":"330100"},
  "forecast": {"date":"2026-10-26","week":"星期一","dayWeather":"多云","nightWeather":"小雨","dayTemp":24,"nightTemp":18,"windDirection":"东北风","windPower":"1-3级"},
  "reason": null,
  "detail": null,
  "source": "amap"
}
~~~

状态：

| status | HTTP | 含义 |
| --- | --- | --- |
| `OK` | 200 | 返回单日预报 |
| `PLACE_REQUIRED` | 200 | 请求未带城市 |
| `PLACE_NOT_FOUND` | 200 | 地理编码没有找到城市/区县 |
| `DATE_OUT_OF_RANGE` | 200 | 高德预报未返回目标日期 |

高德 Key 缺失、网络超时、高德 `status!=1` 或 HTTP 错误时，返回 HTTP 503，响应头 `X-Ai-Error-Code: AI_WEATHER_UNAVAILABLE`，响应体为稳定业务错误。passenger-api 将该错误转为公开 `turn.failed`。

## 4. 契约边界

- 客户端只提交聊天文字；不提交 adcode、坐标、温度、天气结论或 `dayOffset`。
- `dayOffset` 由服务端从受控提取结果传入；默认 0，允许 0..3。
- 地图服务只返回天气事实和稳定状态，不生成客服话术。
- 天气查询不读写 `routeRef`、路线快照、`checkContext` 或 `placeChoiceId`。
- 不新增数据库表、迁移、Gateway 路由或 Redis 依赖。
