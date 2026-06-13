仿滴滴平台-后端

## 本地启动

XXL-JOB Admin 已作为 Maven 子模块纳入本仓库：

```bash
mvn -pl xxl-job-admin spring-boot:run
```

默认访问地址：`http://127.0.0.1:8081/xxl-job-admin`，默认账号：`admin / 123456`。

首次使用如本地没有 `xxl_job` 库，可执行：

```bash
mysql -h127.0.0.1 -uroot < xxl-job-admin/src/main/resources/db/tables_xxl_job.sql
```

## 文档索引

- 总览
  - 《AGENTS.md》
  - 《TODO与差距总览.md》
  - 《功能测试清单.md》
- 乘客/司机闭环
  - 《乘客司机端_最小闭环接口调用文档.md》
  - 《订单与派单_两段式Outbox与Kafka_技术方案.md》
  - 《订单与派单_订单服务幂等与并发方案说明.md》
- 后台管理系统
  - 《后台管理系统_运力配置_PRD.md》
  - 《后台管理系统_运力配置_TECH.md》
  - 《后台管理系统_运力配置_API.md》
  - 《后台管理系统_订单管理_PRD.md》
  - 《后台管理系统_订单管理_TECH.md》
  - 《后台管理系统_订单管理_API.md》
- 司机端（换队）
  - 《司机_换队功能_PRD.md》
  - 《司机_换队功能_TECH.md》
  - 《司机_换队功能_API.md》
