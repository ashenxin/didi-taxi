#!/usr/bin/env bash
# Smoke-test the map-service weather skill.
#
# Usage:
#   MAP_INTERNAL_TOKEN=... ./forecast.sh 杭州 1
#   MAP_BASE_URL=http://127.0.0.1:8094 MAP_INTERNAL_TOKEN=... ./forecast.sh 杭州 1
#
# dayOffset: 0=today, 1=tomorrow, 2=day after, 3=day after that.
set -euo pipefail

BASE_URL="${MAP_BASE_URL:-http://127.0.0.1:8094}"
PLACE="${1:-杭州}"
OFFSET="${2:-1}"

if [[ -z "${MAP_INTERNAL_TOKEN:-}" ]]; then
  echo "MAP_INTERNAL_TOKEN is required (same value as map-service map.internal-auth.token)." >&2
  exit 2
fi
if [[ ! "$OFFSET" =~ ^[0-3]$ ]]; then
  echo "dayOffset must be 0..3, got: $OFFSET" >&2
  exit 2
fi

BODY=$(printf '{"requestNo":"skill-smoke","placeName":"%s","placeRegion":null,"dayOffset":%s}' \
  "$PLACE" "$OFFSET")

curl --fail --silent --show-error \
  -X POST "$BASE_URL/api/v1/internal/ai/weather/forecast" \
  -H "Content-Type: application/json" \
  -H "X-Internal-Service-Token: $MAP_INTERNAL_TOKEN" \
  -d "$BODY"
printf '\n'
