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

- 后台管理系统
  - 《后台管理系统_运力配置_设计.md》
  - 《后台管理系统_运力配置_接口文档.md》
  - 待办汇总：`TODO与差距总览.md`
- 司机端（换队）
  - 《司机_换队功能_产品文档.md》
  - 《司机_换队功能_接口文档（包括后台）.md》
