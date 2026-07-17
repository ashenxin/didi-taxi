# didi-taxi xxl-job-admin

This module vendors XXL-JOB Admin 3.1.0 into the didi-taxi Maven reactor so the scheduler can be started with the rest of the backend services.

## Run

```bash
mvn -pl xxl-job-admin spring-boot:run -Dspring-boot.run.profiles=local
```

Default URL:

```text
http://127.0.0.1:8081/xxl-job-admin
```

Default login is the XXL-JOB upstream default:

```text
admin / 123456
```

## Database

Initialize the scheduler database once:

```bash
mysql -h127.0.0.1 -uroot < xxl-job-admin/src/main/resources/db/tables_xxl_job.sql
```

Runtime configuration can be overridden with environment variables:

```text
XXL_JOB_ADMIN_PORT
XXL_JOB_ADMIN_CONTEXT_PATH
XXL_JOB_DATASOURCE_URL
XXL_JOB_DATASOURCE_USERNAME
XXL_JOB_DATASOURCE_PASSWORD
XXL_JOB_ACCESS_TOKEN
```

The existing `order` and `capacity` executors are already configured to use:

```text
http://127.0.0.1:8081/xxl-job-admin
```
