#!/usr/bin/env bash
# 启动本地中间件并等待全部 healthy。
set -euo pipefail
cd "$(dirname "$0")"

for port in 3306 6379 9092; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "端口 $port 已被占用，先停掉 brew 服务：" >&2
    echo "  brew services stop mysql redis kafka" >&2
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >&2
    exit 1
  fi
done

docker compose up -d

for name in didi-taxi-mysql didi-taxi-redis didi-taxi-kafka; do
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
