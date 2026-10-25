---
name: weather-query
description: >-
  开发、测试和扩展本仓库乘客端 AI 客服的天气查询能力（map-service + passenger-api）。
  当任务涉及天气查询、WEATHER_QUERY、AmapWeatherService、WeatherAnswerFormatter，
  或 二期功能/乘客端_AI客服_天气查询_*.md 时使用。
---

# 天气查询 Skill

## 适用范围

这是乘客端 AI 客服内的一个有界、无长期状态能力：

- 单城市或区县；
- 今天、明天、后天、大后天（`dayOffset` 0–3）；
- 只使用高德预报；
- 文本回答，可选附带 `weather` payload。

它不规划路线、不修改当前路线，也不新增长期天气状态；同一会话的天气追问可由 host 复用上一轮天气回答的城市，并在追问消息 payload 中保存短时 `weatherContext`。

## 何时使用

出现以下任务时使用本 skill：

- 新增或修改天气意图提取（`WEATHER_QUERY`、城市、`dayOffset`）；
- 修改高德天气 provider、状态码或超时；
- 修改天气话术、风向风力归一化或提示；
- 排查“天气不可用 / 城市不对 / 日期不对”；
- 补充或更新天气测试和四份天气文档。

## 何时不要使用

以下场景不要使用本 skill：

- 路线规划、当前路线核查、收费站方向校验；
- 沿途天气、天气预警、空气质量、逐小时预报、多城市对比；
- 声称天气会影响路况、ETA 或道路条件；
- 新增长期天气状态或数据库表；短时 `weatherContext` 只随追问消息 payload 保存，并从公开事件剥离。

## 架构

| 层 | 职责 |
|---|---|
| `map-service` | 城市地理编码为 `adcode`；调用高德 `weatherInfo`；返回结构化天气事实和稳定状态 |
| `passenger-api` | 识别 `WEATHER_QUERY`；校验 `dayOffset`；调用 map-service；组织话术；通过既有 SSE 持久化并下发 |
| `passenger-service` | 只使用既有会话和消息持久化；天气轮次就是一条普通 TEXT 消息 |
| `gateway` | 既有 JWT 和 `/app/**` 路由，不做天气专项改动 |

调用链：

```text
H5 → gateway:18080 → passenger-api:8100
  ├─ map-service extract → intent/city/dayOffset
  └─ map-service /api/v1/internal/ai/weather/forecast
       ├─ AmapGeocodeService → adcode
       └─ GET https://restapi.amap.com/v3/weather/weatherInfo
              ?key=...&city={adcode}&extensions=all
  → WeatherAnswerFormatter → passenger-service complete → SSE answer.completed
```

## 契约

内部请求：

```json
{"requestNo":"REQ-1","placeName":"杭州","placeRegion":null,"dayOffset":1}
```

状态：`OK`、`PLACE_REQUIRED`、`PLACE_NOT_FOUND`、`DATE_OUT_OF_RANGE`；高德失败返回 HTTP 503，并带 `X-Ai-Error-Code: AI_WEATHER_UNAVAILABLE`。

公开成功结果是 `answer.completed` + `payload.weather`；缺城市、日期越界、城市不存在都是正常回答，不是 `turn.failed`。完整字段见 [references/contract.md](references/contract.md)。

## 工作流

1. 先读权威文档：
   - `二期功能/乘客端_AI客服_天气查询_PRD.md`
   - `二期功能/乘客端_AI客服_天气查询_API.md`
   - `二期功能/乘客端_AI客服_天气查询_TECH.md`
   - `二期功能/乘客端_AI客服_天气查询_TEST.md`
2. 修改 provider（`map-service`）时，重点看：
   - `AmapWeatherService`（地理编码、高德调用、日期匹配、风向风力归一化）；
   - `AiWeatherInternalController`（状态与错误映射）；
   - `WeatherForecastRequest` / `WeatherForecastResponse`。
3. 修改 host（`passenger-api`）时，重点看：
   - 提取提示词的意图和字段；
   - `AiRouteTurnService.handleWeatherQuery` 的分发；
   - `WeatherAnswerFormatter` 的话术和 payload。
4. 守住模型边界：模型只提取城市文字和 `dayOffset`，绝不输出温度、天气现象、风力或 adcode。
5. 同步更新测试和四份天气文档。
6. 只运行下面的验证命令；不要启动、停止或重启服务。

## 关键文件

路径相对后端仓库根目录（`didi-taxi/`）：

| 用途 | 路径 |
|---|---|
| 意图枚举 | `map/src/main/java/com/sx/map/ai/model/PassengerRouteIntent.java` |
| 提取提示词 | `map/src/main/resources/ai/passenger-route-extraction-prompt.md` |
| 提取解析 | `map/src/main/java/com/sx/map/ai/service/PassengerRouteExtractionService.java` |
| 天气 provider | `map/src/main/java/com/sx/map/service/AmapWeatherService.java` |
| 内部接口 | `map/src/main/java/com/sx/map/ai/controller/AiWeatherInternalController.java` |
| 内部 DTO | `map/src/main/java/com/sx/map/ai/dto/WeatherForecastRequest.java`、`WeatherForecastResponse.java` |
| BFF Feign 客户端 | `passenger-api/src/main/java/com/sx/passengerapi/client/MapWeatherAiClient.java` |
| BFF DTO | `passenger-api/src/main/java/com/sx/passengerapi/client/dto/maproute/MapWeatherDtos.java` |
| 轮次分发 | `passenger-api/src/main/java/com/sx/passengerapi/service/AiRouteTurnService.java` |
| 上下文城市 | `passenger-api/src/main/java/com/sx/passengerapi/service/WeatherContextResolver.java` |
| 话术格式化 | `passenger-api/src/main/java/com/sx/passengerapi/service/WeatherAnswerFormatter.java` |
| Provider 测试 | `map/src/test/java/com/sx/map/service/AmapWeatherServiceTest.java`、`map/src/test/java/com/sx/map/ai/controller/AiWeatherInternalControllerTest.java` |
| Host 测试 | `passenger-api/src/test/java/com/sx/passengerapi/service/WeatherAnswerFormatterTest.java`、`AiRouteTurnServiceTest.java` |
| H5 提示 | `../didi-taxi-front/didi-passenger-h5/src/features/ai/AiAssistantView.vue` |

## 边界与护栏

- 天气事实只来自高德；缺失字段不编造，显示“天气信息暂缺”或“气温暂缺”。
- 模型只提取文字；状态、日期、天气和话术都由 Java 决定。
- 本轮没有城市时，只有同一会话上一轮已持久化天气回答可以复用城市；显式城市优先，复用必须在正文前说明“按你刚才说的杭州查：”。不跨会话、不使用 GPS、地图页或当前路线。
- 上一轮是天气回答或天气追问时，passenger-api 才向 extract 传 `weatherContinuation=true`；它只是让“明天呢/那里呢”归为天气追问的受控提示，不携带聊天正文。
- 问城市时在持久化 payload 顶层保存私有 `weatherContext.dayOffset`；下一轮只回城市（如“德清”）时用原日期继续天气。若乘客给出路线动作或两端，路线优先。`weatherContext` 必须从实时 SSE、历史和请求状态中剥离。
- `dayOffset`：今天=0、明天=1、后天=2、大后天=3；`null` 按今天；`-1` 或 0–3 之外表示不支持。
- 展示层幂等归一化单位：`东北` → `东北风`，`1-3` → `1-3级`。
- 提示只基于天气和温度，当前规则：有雨提示带伞；有雪提示保暖；最高温 ≥35℃ 提示防暑；最低温 ≤0℃ 提示保暖。没有紫外线数据，因此普通晴天不提示防晒。
- 不使用 Redis，不新增数据库表，不读写 `routeRef`、`checkContext` 或 `placeChoiceId`。
- 不记录密钥、完整聊天正文或坐标。

## 验证

Provider 和 host 测试：

```bash
mvn -q -pl map -Dtest='AmapWeatherServiceTest,AiWeatherInternalControllerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test

mvn -q -pl passenger-api -Dtest='WeatherAnswerFormatterTest,AiRouteTurnServiceTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test

git diff --check
```

本机 `mvn` 不在 `PATH` 时，使用 IDEA 自带 Maven：

```bash
"/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn" ...
```

如果改了 H5 提示，再执行：

```bash
cd ../didi-taxi-front/didi-passenger-h5
npm test
npm run build
```

手工场景和日志排查见 [references/verification.md](references/verification.md)。
