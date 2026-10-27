#!/usr/bin/env bash
# 启动本地基础设施（MySQL / Redis / Kafka / Nacos）并等待全部 healthy。
set -euo pipefail
cd "$(dirname "$0")"

# 端口只做"非 Docker 进程占用"预检：本项目容器已运行时端口当然被占，交给 compose 处理。
for port in 3306 6379 9092 8848; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    owner=$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -F c 2>/dev/null | sed -n '2p' | cut -c2-)
    case "$owner" in
      com.docke*) ;;
      *)
        echo "端口 $port 被进程 $owner 占用，先停掉它" >&2
        lsof -nP -iTCP:"$port" -sTCP:LISTEN >&2
        exit 1
        ;;
    esac
  fi
done

docker compose up -d

for name in didi-taxi-mysql didi-taxi-redis didi-taxi-kafka didi-taxi-nacos; do
  status=unknown
  for _ in $(seq 1 60); do
    status=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$name" 2>/dev/null || echo missing)
    [ "$status" = "healthy" ] && break
    sleep 2
  done
  printf '%-20s %s\n' "$name" "$status"
  if [ "$status" != "healthy" ]; then
    echo "启动失败，查看日志：docker compose logs $name" >&2
    exit 1
  fi
done

echo
docker compose ps
