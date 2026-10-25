# 天气查询验证

## 1. 前置条件

- `map-service`（8094）和 `passenger-api`（8100）已用当前代码启动；`gateway`（18080）、`passenger-service`（8092）、Nacos、MySQL、Redis 保持运行。
- 已配置 `map.amap.key`（或 `AMAP_KEY` / `MAP_AMAP_KEY`），且 Key 已开通 **Web 服务 API → 天气查询** 权限。
- `map-service` 与 `passenger-api` 的 `map.internal-auth.token` 相同。
- 验证过程不要启动、停止或重启服务；服务由用户在 IDEA 中运行。

## 2. 测试矩阵

| 编号 | 输入 | 预期 |
|---|---|---|
| W1 | 杭州明天天气 | `answer.completed`；正文包含城市、日期、星期、白天/夜间天气、温度、归一化风力；有雨时带伞提示 |
| W2 | 杭州天气怎么样 | 今天预报（`dayOffset=0`） |
| W3 | 明天会下雨吗 | 追问城市；不调用高德 |
| W4 | 下周六杭州天气 | 说明只支持四天；不调用高德 |
| W5 | 不存在市今天天气 | “没有找到……请补充省份或换一个名称”；不返回 `turn.failed` |
| W6 | 从 A 到 B，这条路线经过 X 吗 | 走路线核查，不进天气 |
| W7 | 高德超时/Key/配额错误 | `turn.failed`，错误码 `AI_WEATHER_UNAVAILABLE` |
| W8 | 晴/晴、18~30℃ | 没有雨雪/高温/低温提示；普通晴天不加防晒提示（当前产品决定） |
| W9 | 东北 + 1-3 | 正文 `东北风 1-3级`；payload 同样归一化 |
| W10 | 天气轮次前后已有路线卡 | `routeRef` 和路线状态不变 |
| W11 | 杭州明天天气 → 后天呢 | 复用杭州，正文以“按你刚才说的杭州查：”开头 |
| W12 | 杭州明天天气 → 上海明天天气 | 使用上海，不出现“按你刚才说的”前缀 |
| W13 | 路线卡之后 → 明天天气 | 没有天气上下文，仍追问城市 |
| W14 | 后天会下雨吗 → 德清 | 用德清和后天继续天气，不追问起终点；公开流不出现 `weatherContext` |

## 3. 自动化测试

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

当前预期数量：

- `AmapWeatherServiceTest`：7
- `AiWeatherInternalControllerTest`：2
- `WeatherAnswerFormatterTest`：3
- `PassengerRouteExtractionServiceTest`：20
- `AiRouteTurnServiceTest`：52

如果改了 H5 提示：

```bash
cd ../didi-taxi-front/didi-passenger-h5
npm test
npm run build
```

## 4. 手工 H5 验证

1. 打开乘客 H5 的 AI 助手，点“新对话”。
2. 确认空状态显示“路线和天气，都可以问我”，并有“杭州明天天气怎么样？”建议按钮。
3. 发送“杭州明天天气”。
4. 确认回答包含星期、白天/夜间天气、气温、带单位的风力，以及适用时的提示。
5. 在同一个天气会话里发送“后天呢”，确认正文以“按你刚才说的杭州查：”开头；再发“上海明天天气”，确认使用上海且没有该前缀。
6. 新会话发“后天会下雨吗”，确认追问城市；再回“德清”，确认继续天气、不追问起终点。
7. 点“新对话”后发送“明天会下雨吗”，确认没有天气上下文时追问城市。
8. 发送“下周六杭州天气”，确认四天限制说明。
9. 发送“不存在市今天天气”，确认“没有找到”而不是 `turn.failed`。
10. 在已有路线卡的会话里再问一次天气，确认路线卡和当前路线没有变化；若中间已换成路线卡，再问“明天天气”应追问城市，而不是复用旧天气城市。

## 5. 日志

- map-service：`map/logs/map-service/map-service.log`
  - 成功：`高德天气查询成功 place=... adcode=... date=...`
  - 失败：`高德天气查询失败 ...` 或 `高德天气查询暂时不可用`
- passenger-api：`passenger-api/logs/passenger-api/passenger-api.log`
  - 失败：`AI 客服轮次失败 ... code=AI_WEATHER_UNAVAILABLE`

不要粘贴或记录 Key、完整聊天正文或坐标。

## 6. 常见问题

| 现象 | 可能原因 | 处理 |
|---|---|---|
| 天气服务暂时不可用 | Key 缺失/无效、未开通天气权限、配额或超时 | 查 map-service 日志和高德控制台 |
| 总是“请告诉我城市” | 提取结果 `weatherPlaceName` 为空；原话没有城市 | 让乘客发完整问法，如“杭州明天天气” |
| 用户说“明天”却日期越界 | `weatherDayOffset` 提取错误 | 检查提取提示词和模型输出；`-1` 只用于四天之外的日期 |
| 仍显示“东北1-3” | 旧 map-service/passenger-api 还在运行 | 重启两个服务；formatter 的归一化是幂等的 |
| 路线问题被当成天气回答 | 提示词优先级回归 | 保证路线起终点/动作优先于天气，并补提示词测试 |
