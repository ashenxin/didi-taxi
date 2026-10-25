# 乘客端 AI 客服：天气查询 skill TECH

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v1.0 |
| 日期 | 2026-10-25 |
| 状态 | 第一版代码完成，待运行环境与真实页面验收 |
| 产品依据 | 乘客端_AI客服_天气查询_PRD.md |
| 接口依据 | 乘客端_AI客服_天气查询_API.md |
| 复用 | AI 客服会话、SSE 轮次、内部 token 和 map-service 高德适配器 |

## 1. 职责边界

| 组件 | 职责 | 不负责 |
| --- | --- | --- |
| passenger-api | 识别 `WEATHER_QUERY`、校验日期偏移、调用天气 skill、组织乘客话术、持久化消息和发送 SSE | 不解析 adcode、不调用高德、不编造天气 |
| map-service | 城市文本 → adcode → 高德预报；返回结构化事实和稳定状态 | 不保存会话、不生成话术、不读写路线状态 |
| passenger-service | 既有会话与消息持久化；天气轮次仍是一条普通 TEXT 消息 | 不保存专用天气状态；短时 `weatherContext` 随消息 payload 落库 |
| gateway | 既有 JWT 和路由 | 无新增配置 |

天气 skill（map-service）是无状态的叶子能力，不进入路线编排状态机，也不产生 `checkContext`；passenger-api 只从上一轮已持久化天气消息恢复短时城市/日期上下文。

## 2. 调用链

```
H5
 → gateway:18080
 → passenger-api:8100
     ├─ map-service extract: 识别 WEATHER_QUERY，提取城市与日期偏移
     └─ map-service /api/v1/internal/ai/weather/forecast
          ├─ AmapGeocodeService.geocode(placeName, placeRegion) → GeocodeDemoResponse.adcode
          └─ GET https://restapi.amap.com/v3/weather/weatherInfo
                 ?key=...&city={adcode}&extensions=all
 → passenger-api 组织文本与 weather payload，passenger-service complete 落库，SSE answer.completed
```

高德天气接口要求 `city` 传 adcode，`extensions=all` 返回预报，见[天气查询文档](https://lbs.amap.com/api/webservice/guide/api/weatherinfo)。

## 3. 关键实现

### 3.1 意图与提取

- `PassengerRouteIntent` 新增 `WEATHER_QUERY`。
- `ExtractEndpointsResponse` 新增 `weatherPlaceName`、`weatherPlaceRegion`、`Integer weatherDayOffset`；保留旧构造器，旧调用默认天气字段为 null。
- `PassengerRouteExtractionService` 只对 `WEATHER_QUERY` 读取三个天气字段；`weatherPlaceName` 必须出现在乘客原话中，否则置 null。
- `weatherDayOffset`：今天=0、明天=1、后天=2、大后天=3；null 按今天；明确超出四天为 -1。模型不输出温度、天气现象或 adcode。
- passenger-api 在 `begin` 返回的上一轮 assistant 是已持久化天气回答时，向 extract 请求传 `weatherContinuation=true`；该字段只是受控提示，不携带聊天正文，城市仍由 host 从上一轮 payload 恢复。

### 3.2 高德天气

- `AmapWeatherService.forecast(placeName, placeRegion, dayOffset)`：
  1. 校验 Key 和城市；城市为空返回 `PLACE_REQUIRED`。
  2. 校验偏移 0..3；超出返回 `DATE_OUT_OF_RANGE`，不调用高德。
  3. 复用 `AmapGeocodeService` 取首条地理编码的 `adcode`；没有结果返回 `PLACE_NOT_FOUND`。
  4. 调用高德天气，`status` 必须为 `1`，`forecasts[0].casts` 非空。
  5. 以 `Asia/Shanghai` 的今天加偏移得到目标日期，按 `date` 匹配 `casts`，不按数组下标假设固定四天。
  6. 返回 `WeatherForecastResponse`，温度字符串解析为整数；缺失字段保留 null。
- `AiWeatherInternalController` 暴露 `/api/v1/internal/ai/weather/forecast`；`AmapApiException` 统一转为 HTTP 503 + `AI_WEATHER_UNAVAILABLE`。
- 天气结果不进入 Redis；当前不缓存。高德调用量或延迟需要优化时，再按 adcode 增加短时缓存。

### 3.3 话术与 SSE

- `WeatherAnswerFormatter` 只做格式化：
  - 地点优先使用乘客原话，其次地图规范名；
  - 日期标签按 `dayOffset`，括号内使用高德日期和星期；高德 `week` 为 `1`～`7` 时转成“星期一”～“星期日”；
  - 白天/夜间天气分别说明；相同则显示“全天X”；温度取夜间最低~白天最高，缺失显示“气温暂缺”；
  - 风向缺失“风”、风力缺失“级”时在展示层幂等补齐，例如“东北”+“1-3”显示为“东北风 1-3级”；
  - 有雨提示带伞，有雪提示保暖，高温/低温提示防暑或保暖；只根据天气和温度给通用提示，不推断路况、拥堵或 ETA。
- `WeatherContextResolver`：本轮没有显式城市时，只从同一会话上一轮已持久化天气 payload 的 `location` 恢复城市；显式城市优先；不跨会话、不使用 GPS、地图页或当前路线。复用结果在正文前加“按你刚才说的杭州查：”，让乘客可以纠正。
- 天气追问保存私有 `weatherContext`：`completeWeatherAsk` 把原问题的 `dayOffset` 写入持久化 payload，`clientPayload`/历史/请求状态统一剥离。下一轮 `WeatherContextResolver` 读取该字段；若乘客只回一个城市（如“德清”），`weatherReplyFromPending` 用原日期继续天气；若乘客给出两端或路线动作（从/到/规划/路线/经过/途经/改走/导航/怎么走/去/回），返回 null，路线分支优先。
- 待补城市时正文按已知日期收敛为“请告诉我城市，例如：杭州后天天气。”；日期未知才说“请告诉我城市和日期”。
- `AiRouteTurnService.handleWeatherQuery`：
  - 缺城市且没有可复用天气上下文 → `answer.completed` 追问；
  - 偏移超范围 → `answer.completed` 说明；
  - `OK` → `answer.completed` + `payload.weather`；
  - `PLACE_NOT_FOUND` → 可读说明，不 `turn.failed`；
  - 高德/内部接口故障 → `turn.failed` `AI_WEATHER_UNAVAILABLE`。
- 天气轮次不做 `prepare`、`defaultRoute`、`checkCurrentRoute`，不读写 `routeRef`。

## 4. 失败与超时

| 失败 | 处理 |
| --- | --- |
| 城市为空 | `PLACE_REQUIRED`，passenger-api 追问，不调用高德 |
| 日期超出 0..3 | `DATE_OUT_OF_RANGE`，不调用高德 |
| 地理编码无结果 | `PLACE_NOT_FOUND`，正常回答 |
| 高德 Key 缺失、网络错误、HTTP 错误、`status!=1` | `AmapApiException` → 503 `AI_WEATHER_UNAVAILABLE` → `turn.failed` |
| 响应无 `forecasts`/`casts` 或目标日期不存在 | 视为上游异常或 `DATE_OUT_OF_RANGE`，不编造 |

复用 `amapRestClient` 的 5 秒连接 / 15 秒读取超时；passenger-api 调 map-service 的内部 Feign 仍为 1 秒连接 / 30 秒读取。一次天气查询通常包含一次地理编码和一次天气请求，生产 P95 预算仍需压测校准。

## 5. 安全与隐私

- 高德 Key 只在 Nacos/环境变量中，不进仓库、响应或日志。
- 内部接口由 `X-Internal-Service-Token` 保护，token 与 passenger-api 的 `map.internal-auth.token` 一致。
- 日志只记录城市、adcode、日期、状态和耗时，不记录完整聊天正文。
- payload 不含坐标、原始 POI、密钥或导航数据。

## 6. 已知限制

- 地理编码取首条结果，同名跨城市可能选错；第一版通过正文显示规范城市名让乘客自行纠正，后续可返回城市候选。
- 高德返回城市/区县级预报，不代表具体路段或路口精度。
- 不做沿途天气、天气预警、空气质量、天气对路况/ETA 的影响。
- 天气 skill（map-service）本身不保存上下文；passenger-api 可从上一轮已持久化天气回答恢复城市，也可从追问消息的 `weatherContext.dayOffset` 恢复日期；不跨会话、不使用 GPS、地图页或当前路线。乘客改为路线动作或给出两端时，路线分支优先，天气上下文不生效。

## 7. 测试

- `AmapWeatherServiceTest`：成功按日期选择、空城市、越界日期、城市不存在、高德业务错误、HTTP 错误、缺 Key。
- `AiWeatherInternalControllerTest`：结构化成功响应、503 + `AI_WEATHER_UNAVAILABLE` 错误头。
- `PassengerRouteExtractionServiceTest`：天气字段解析、原话城市校验、非天气意图清空天气字段。
- `AiRouteTurnServiceTest`：成功回答、缺城市、日期越界、城市不存在、上游失败、路由不被抢走，以及“天气追问复用上一轮城市并披露”“显式城市覆盖”“无天气上下文仍追问”。
- 真实环境仍需验证高德 Key 的天气权限、配额、超时和真实城市/区县结果。
