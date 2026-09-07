# 司机端行程记录、详情与今日运营看板 SQL

数据库由用户手动执行。本轮没有连接业务库建表或迁移。

## 1. 先建表

文件：[order_driver_trip_patch.sql](../order/src/main/resources/sql/order_driver_trip_patch.sql)。
目标：已有 MySQL 8 的 `order` 库；依赖既有 trip_order 主表，不修改其字段。
建表脚本可重复执行，已存在的同名表不会被重建。完成后检查 SHOW CREATE TABLE driver_trip_record。
请在新代码上线接单前执行；旧代码可继续运行，但回填应在暂停订单写流量的维护窗口进行。

## 2. 历史回填

文件：[order_driver_trip_backfill.sql](../order/src/main/resources/sql/order_driver_trip_backfill.sql)。
先按接单事件重建每次服务，再补缺少事件但现有订单有明确接单时间的记录。可证明的终态和当前服务才入库，缺失字段留空。
脚本在暂停订单写流量的维护窗口执行，只插入缺失项，可重复执行；没有在业务 MySQL 执行，本次自动化只验证 H2/MySQL 模式的核心行为。
不要简单按 trip_order.driver_id 回填已取消司机，因为改派后该字段会被清空或替换。
执行回填前核对范围并备份；回填后检查同单多司机、终态、金额隔离和今日统计。

## 3. 代码中的对应脚本

- order/src/main/resources/sql/order_driver_trip_patch.sql：存量库新增表。
- order/src/main/resources/sql/order_driver_trip_backfill.sql：存量订单按可证明事件历史回填。
- order/src/main/resources/sql/order_schema.sql：新库基线已包含相同结构。
- order/src/test/resources/schema-test.sql：自动化测试 H2 结构，不用于业务库。
