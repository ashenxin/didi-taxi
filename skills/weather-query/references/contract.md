# 天气查询契约

## 1. 提取

`POST /api/v1/internal/ai/route/extract`（既有接口）在 `intent=WEATHER_QUERY` 时返回：

| 字段 | 类型 | 含义 |
|---|---|---|
| `weatherPlaceName` | string/null | 乘客原话中的城市/区县片段；不补全、不改写 |
| `weatherPlaceRegion` | string/null | 明确提到的省份/城市/行政区；没有则为 null |
| `weatherDayOffset` | integer/null | 0=今天、1=明天、2=后天、3=大后天；null=今天；-1=超出支持的四天 |

非天气意图的三个字段均为 null。消息同时包含路线起终点或路线规划/核查动作时，路线意图优先；只有天气是唯一诉求时才返回 `WEATHER_QUERY`。

内部 extract 请求可带 `weatherContinuation=true`，仅表示上一轮是天气回答，用于让“明天呢/那里呢”归为天气追问；它不携带聊天正文，城市仍由 passenger-api 从上一轮 payload 恢复。

## 2. 内部请求

```http
POST /api/v1/internal/ai/weather/forecast
X-Internal-Service-Token: <MAP_INTERNAL_TOKEN>
Content-Type: application/json
```

```json
{"requestNo":"REQ-1","placeName":"杭州","placeRegion":null,"dayOffset":1}
```

- `placeName` 是乘客文字，由 map-service 做地理编码。
- `placeRegion` 可选。
- `dayOffset` 为 0–3；null 或省略按 0 处理。

## 3. 内部响应

```json
{
  "status": "OK",
  "location": {
    "name": "杭州市",
    "province": "浙江省",
    "city": "杭州市",
    "district": "西湖区",
    "adcode": "330100"
  },
  "forecast": {
    "date": "2026-10-26",
    "week": "星期一",
    "dayWeather": "多云",
    "nightWeather": "小雨",
    "dayTemp": 24,
    "nightTemp": 18,
    "windDirection": "东北风",
    "windPower": "1-3级"
  },
  "reason": null,
  "detail": null,
  "source": "amap"
}
```

状态：

| status | HTTP | 含义 | Host 行为 |
|---|---|---|---|
| `OK` | 200 | 找到预报 | 组织话术并发送 `answer.completed` |
| `PLACE_REQUIRED` | 200 | 请求没有城市 | 追问城市；不调用高德 |
| `PLACE_NOT_FOUND` | 200 | 地理编码没有结果 | 说明并请乘客补充省份或换名称 |
| `DATE_OUT_OF_RANGE` | 200 | 高德没有目标日期 | 说明只支持四天 |
| 高德/Key/网络错误 | 503 | 上游失败 | `turn.failed` + `AI_WEATHER_UNAVAILABLE` |

高德原始 `week` 可能是 `1`–`7`；map-service 或 formatter 会转成“星期一”–“星期日”。风向和风力做幂等归一化：`东北` → `东北风`，`1-3` → `1-3级`。

## 4. 公开 SSE

成功：

```text
event: answer.completed
data: {"requestNo":"AIR-weather-1","assistantMessage":{"messageType":"TEXT","content":"杭州明天（10月26日，星期一）白天多云，夜间小雨，气温 18~24℃，东北风 1-3级。夜间有雨，出门记得带伞。","payload":{"weather":{...}}}}
```

`payload.weather`：

| 字段 | 说明 |
|---|---|
| `location.name/province/city/district/adcode` | 展示地点；adcode 仅供服务端/排障使用 |
| `date` | `yyyy-MM-dd` |
| `week` | 中文星期文本 |
| `dayWeather` / `nightWeather` | 高德白天/夜间天气现象 |
| `dayTemp` / `nightTemp` | 摄氏度；可缺省 |
| `windDirection` / `windPower` | 已归一化单位 |
| `source` | `amap` |

正常回答（不是 `turn.failed`）：

- 没有城市：日期已知时“请告诉我城市，例如：杭州后天天气。”；日期未知时“请告诉我城市和日期，例如：杭州明天天气。”
- 日期越界：“目前只能查今天、明天、后天、大后天这四天的预报，请换个日期。”
- 城市不存在：“没有找到「X」这个城市，请补充省份或换一个名称。”

## 5. 话术规则

- 地点：优先乘客原话，其次地图规范名。
- 日期标签：按 `dayOffset` 显示今天/明天/后天/大后天；括号内放日期和星期。
- 天气：白天、夜间分别说明；相同显示“全天X”；只有一边显示“白天X”或“夜间X”。
- 温度：`气温 min~max℃`；缺失显示“气温暂缺”。
- 风力：`东北风 1-3级`；只有方向或只有风力时省略缺失部分。
- 提示：有雨提示带伞；有雪提示保暖；最高温 ≥35℃ 提示防暑；最低温 ≤0℃ 提示保暖。没有紫外线数据，普通晴天不提示防晒。不推断交通、ETA 或路况。

## 6. 上下文城市复用

- 本轮文字有显式城市 → 始终使用显式城市。
- 本轮没有城市，且同一会话上一轮是已持久化的天气回答（`messageType=TEXT`、`status=COMPLETED`，payload 含 `weather.location`）→ 从 `location.city` / `name` / `district` 依次恢复城市。
- 复用只在 host（passenger-api）发生；weather provider 仍是无状态的。
- 复用结果在正文前加“按你刚才说的杭州查：”，让乘客可以纠正；显式城市覆盖复用。
- 不跨会话、不读取完整聊天历史、不使用 GPS、地图页或当前路线城市。
- 没有可复用天气上下文时，仍返回“请告诉我城市和日期，例如：杭州明天天气。”；如果原问题日期已知，则收敛为“请告诉我城市，例如：杭州后天天气。”
- 天气追问本身也保存上下文：缺城市时在持久化 payload 顶层写 `weatherContext.dayOffset`；下一轮只回城市（如“德清”）时用原日期继续天气。该字段不进入公开 SSE、历史或请求状态。
- 若乘客在“待补城市”后改为路线动作或给出两端（从/到/规划/路线/经过/途经/改走/导航/怎么走/去/回），路线分支优先，天气上下文不生效。

示例：

```text
杭州明天天气 → 后天呢
按你刚才说的杭州查：杭州后天（10月27日，星期二）…

杭州明天天气 → 上海明天天气
上海明天（10月26日，星期一）…   （不出现“按你刚才说的”前缀）

杭州明天天气 → 明天天气（中间已换成路线卡）
请告诉我城市和日期，例如：杭州明天天气。

后天会下雨吗 → 请告诉我城市，例如：杭州后天天气。 → 德清
德清后天（10月27日，星期二）…
```
