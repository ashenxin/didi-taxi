#!/usr/bin/env bash
# 预建业务 topic，分区数与迁移前集群保持一致。
# 代码里没有 KafkaAdmin/NewTopic，topic 靠 broker 自动创建，而自动创建只会用 num.partitions，
# 不预建会把 3 分区的 account.lifecycle.* 变成 1 分区。
set -euo pipefail
cd "$(dirname "$0")"

KAFKA_BIN=/opt/kafka/bin
BOOTSTRAP=localhost:9092

create() {
  local topic=$1 partitions=$2
  docker compose exec -T kafka "$KAFKA_BIN/kafka-topics.sh" \
    --bootstrap-server "$BOOTSTRAP" \
    --create --if-not-exists \
    --topic "$topic" \
    --partitions "$partitions" \
    --replication-factor 1 >/dev/null
  printf '%-42s %s 分区\n' "$topic" "$partitions"
}

create account.lifecycle.event.v1          3
create account.lifecycle.result.v1         3
create account.lifecycle.command.v1        3
create account.lifecycle.requested.v1      1
create account.lifecycle.phone-changed.v1  1
create order.changed.v1                    1
create order.dispatch.requested.v1         1
create order.settlement.requested.v1       1

echo
docker compose exec -T kafka "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --describe |
  awk '{print $2, $4, $6}' | sort -u
