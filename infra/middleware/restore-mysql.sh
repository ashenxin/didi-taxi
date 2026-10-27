#!/usr/bin/env bash
# 把 mysqldump 备份导入 MySQL 容器，并打印各库表数量。
# 用法：./restore-mysql.sh [dump 文件路径]   默认取 .local/ 下最新的 *.sql
set -euo pipefail
cd "$(dirname "$0")"

dump=${1:-$(ls -t .local/*.sql 2>/dev/null | head -1 || true)}
if [ -z "${dump:-}" ] || [ ! -f "$dump" ]; then
  echo "找不到 dump 文件：${dump:-<空>}" >&2
  exit 1
fi

echo "导入 $dump ..."
docker compose exec -T mysql mysql -uroot --default-character-set=utf8mb4 < "$dump"

echo "=== 各库表数量 ==="
docker compose exec -T mysql mysql -uroot -N -e "
  select table_schema, count(*)
  from information_schema.tables
  where table_schema in ('calculate','capacity','order','passenger','wallet','xxl_job')
  group by table_schema order by table_schema;"
