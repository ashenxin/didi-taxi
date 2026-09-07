# 司机端行程记录、详情与今日运营看板 API

> 2026-09-07 已实现。浏览器统一走 gateway:18080，Bearer token 必填；用户身份由网关注入，客户端不传 driverId。

统一响应：`{ "code": 200, "msg": "success", "data": ... }`。401 重新登录，404 行程不存在（含越权），400 参数错误，502/504 下游异常。

## 1. 行程分页

`GET /driver/api/v1/profile/orders`

| 参数 | 默认 | 说明 |
|---|---|---|
| status | ALL | ALL / IN_PROGRESS / FINISHED / CANCELLED |
| startDate / endDate | 空 | YYYY-MM-DD，包含首尾日期；按上海时区解释 |
| dateField | ACCEPTED | ACCEPTED / FINISHED / CANCELLED；今日看板跳转使用对应事件时间 |
| pageNo / pageSize | 1 / 20 | 页码 1～100000，每页 1～100 |
| snapshotId | 空 | 首次请求返回的字符串；后续翻页原样传回；刷新/改筛选时清除 |

响应 data：`{ list: DriverTripView[], total, pageNo, pageSize, snapshotId }`。
按 `acceptedAt DESC, id DESC` 排序，snapshotId 固定本轮最大记录 ID，避免新接单插入使翻页重复；历史状态变化后需刷新重新查询，不承诺跨多次 HTTP 的数据库快照。

## 2. 行程详情

`GET /driver/api/v1/profile/orders/{tripId}`

tripId 为分页返回的 `id`，不是 orderNo；改派导致同一 orderNo 有多条司机行程。
data 为 DriverTripView，仅当前司机自己的记录可见，越权和不存在同为 404。
原 `GET /driver/api/v1/orders/{orderNo}` 保留用于当前行程动作跟踪，语义不变。

### DriverTripView

- id：字符串；orderNo：订单号；driverId、carId、companyId：当次司机/车辆/车队标识。
- cityCode、productCode、originAddress、destAddress。
- status：2已接单、3已到达、4行程中、5已完成、6已取消。
- orderedAt、acceptedAt、arrivedAt、startedAt、finishedAt、cancelledAt、updatedAt：上海本地日期时间。
- cancelBy：1乘客、2司机、3系统；cancelReason：当次取消原因。
- estimatedAmount：下单预估；finalAmount：优惠前最终车费；discountAmount：优惠；payableAmount：应付；paidAmount：已付。
- settlementStatus：CALCULATING / PAYMENT_REQUIRED / PAY_CONFIRMING / PAID；manualActionRequired：1需人工处理。
- distanceMeters、distanceSource：冻结里程及来源；serviceDurationSeconds：开始到完成用时；billingDurationSeconds：结算计费时长。
- 未知值为 null，不用 0 替代未知；已取消记录所有后续结算字段均为空。

## 3. 今日运营

`GET /driver/api/v1/dashboard/today`

- businessDate、timezone=Asia/Shanghai、periodStart、periodEnd（右开）、asOf。
- completedCount、cancelledCount、driverCancelledCount、passengerCancelledCount、systemCancelledCount。
- orderAmount：今日完成且最终金额已确定、无人工处理标记的车费总和；不包含预估。
- unpricedCount：今日完成但最终金额为空的单数；abnormalSettlementCount：需人工处理的完成单数（可与未定价重叠）。
- distanceMeters、serviceDurationSeconds：今日完成记录的非负已知值之和；missingDistanceCount、missingDurationCount 提示数据缺失。
- latestTrip：今日最近完单的 DriverTripView，无数据为 null。

## 4. 内部调用

BFF 经服务发现调用 order-service：`GET /api/v1/driver-trips`、`GET /api/v1/driver-trips/{tripId}`、`GET /api/v1/driver-trips/today`。
内部显式传递可信 X-User-Id；Order 查询条件包含 driver_id。前端不直连内部路径。
