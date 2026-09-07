# 司机端行程记录、详情与今日运营看板 TECH

## 1. 数据与职责

order 新增 driver_trip_record：一条记录代表一次成功接单服务。唯一 source_key 关联接单事件，唯一 (order_no, active_flag) 保证一单最多一条活动服务（活动为1，终态NULL）。
接单、到达、开始、完成、接单后取消在订单 CAS 成功的同一事务维护记录；拒单/确认超时不建记录。事务失败同时回滚状态、事件和记录。
金额以 trip_order_settlement 为权威；只有本条记录已完成且仍匹配最终承运司机才关联账单。取消记录不关联后续司机账单。
记录保留当时车队/车辆ID，当前模型没有名称快照，不通过当前司机归属补造历史名称。
升级时已接单订单在首次合法推进时可以从既有 accepted_at 等字段补录；没有接单时间则不推断历史事实。

## 2. 查询

DriverTripRecordMapper 自定义 SQL 执行 LIMIT/OFFSET、COUNT 和数据库 SUM，避免全量内存分页及逐行远程请求。
分页使用 accepted_at+id 排序，首屏 max(id) 作为 snapshotId，后续排除新插入记录。筛选变化和刷新重新取快照。
单次列表+总数、看板+最近一单在只读 REPEATABLE_READ 事务查询；跨 HTTP 的状态变更需要刷新，不宣称完全快照分页。
(driver_id,accepted_at,id)、(driver_id,status,finished_at,id)、(driver_id,status,cancelled_at,id) 支撑列表与统计。
BFF 使用独立 DTO 和 Feign contextId，Controller 只取可信身份，Order 再加归属条件；未知记录和越权统一404。

## 3. 前端

独立 history API、行程列表/详情组件和今日看板组件，复用现有认证与 HTTP 错误处理。
历史页不卸载整个 App，保持心跳和派单 WS，提供返回工作台入口。
取消/完单触发刷新版本；页面可见和跨日刷新；结算中的记录有限轮询且卸载清理。使用请求代次避免切换筛选后旧请求覆盖新结果。

## 4. 数据库交付

由用户手动执行 `order/src/main/resources/sql/order_driver_trip_patch.sql`；存量订单如需纳入行程历史，再执行同目录的 `order_driver_trip_backfill.sql`。新库基线与这两份脚本保持同步。
新表含 order_id 外键，仅订单被物理删除时级联删除；正常逻辑删除和账号注销不删除行程历史。
生产保持现有订单保留策略，不物理清理订单。MySQL8 历史回填与部署顺序见 SQL 文档。
部署必须先建表；否则新增接单等事务会因缺表失败，不能先启动新版本承接写流量。

## 5. 验证

订单 H2 集成测试覆盖真实状态写、回滚与统计；BFF MockMvc 验证身份和异常；前端契约检查、构建和交互验证。
H2 不替代用户环境的 MySQL DDL/回填执行及网关真实链路验收；结果记录到同名 TEST。
