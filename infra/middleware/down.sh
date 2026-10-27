#!/usr/bin/env bash
# 停止本地中间件。注意：故意不使用 -v，数据卷会保留。
set -euo pipefail
cd "$(dirname "$0")"
docker compose down
echo "已停止；数据卷 didi-taxi-{mysql,redis,kafka}-data 保留。"
