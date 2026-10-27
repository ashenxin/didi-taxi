# 本地基础设施（Docker）

MySQL / Redis / Kafka / Nacos 的 Compose 编排。端口与连接方式与迁移前本地安装版本一致，
因此 Nacos 中 10 个 Data ID、`xxl-job-admin` 的默认配置以及各服务的 `NACOS_SERVER_ADDR`
都不需要改动。

| 组件 | 宿主地址 | 镜像 |
| --- | --- | --- |
| MySQL | `127.0.0.1:3306`，root / 空密码 | `mysql:26.7.0` |
| Redis | `127.0.0.1:6379`，无密码 | `redis:8.10.3` |
| Kafka | `127.0.0.1:9092`（KRaft 单节点） | `apache/kafka:4.3.1` |
| Nacos | `8848` 主端口、`9848/9849` gRPC、`8080` 控制台，`nacos / nacos` | `nacos/nacos-server:v3.2.3` |

## 用法

```bash
./up.sh          # 启动并等待所有容器 healthy（端口被占用时会直接报错）
./down.sh        # 停止，保留数据卷
docker compose ps
docker compose logs -f nacos
./init-kafka-topics.sh    # 仅首次：按原分区数预建业务 topic
./restore-mysql.sh        # 仅首次：导入 .local/ 下最新的 dump
```

## 注意

- `restart: "no"`：容器不会随 Docker Desktop 或登录自启；停止后不会自动拉起。
  要彻底不占内存，不用时退出 Docker Desktop 本身。
- 数据在命名卷 `didi-taxi-{mysql,redis,kafka,nacos}-data` 中。`down.sh` 不带 `-v`，不会删数据。
- `.local/` 放 dump、导出的配置等本地产物，已 gitignore；MySQL 首次启动不会自动导入，需手动执行
  `./restore-mysql.sh`。
- Nacos 的鉴权三件套（identity key/value、token）在 `.env` 中，**与迁移前本地 conf 一致**；
  不传这些变量时镜像默认关闭鉴权，服务会因拿不到 token 而启动失败。
- Nacos 数据卷里是嵌入式 derby（`derby-data`）+ 配置导出（`tenant-config-data`）。
  服务实例的 naming 状态不迁移，服务重启后会自动重新注册。
