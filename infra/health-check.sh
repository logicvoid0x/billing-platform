#!/usr/bin/env bash
# Checks that the local environment is healthy: Kafka with every topic, Postgres with the service
# schemas, Flink with a TaskManager, SeaweedFS S3 with its buckets, the Iceberg REST catalog, and
# Kafka Connect with the Iceberg sink plugin. Exits non-zero if anything is unhealthy.
set -uo pipefail

# pwd -W: a Windows path under Git Bash, where docker gets paths unconverted (MSYS_NO_PATHCONV).
here="$(cd "$(dirname "$0")" && (pwd -W 2>/dev/null || pwd))"
# MSYS_NO_PATHCONV: Git Bash on Windows would rewrite container paths (/opt/...) given to docker.
compose=(env MSYS_NO_PATHCONV=1 docker compose -f "$here/docker-compose.yml")
failures=0

check() {
  local name=$1
  shift
  if out=$("$@" 2>&1); then
    printf '  ok    %s\n' "$name"
  else
    printf '  FAIL  %s\n' "$name"
    printf '%s\n' "$out" | tail -3 | sed 's/^/          /'
    failures=$((failures + 1))
  fi
}

http_ok() { curl -fsS -m 5 -o /dev/null "$1"; }
http_has() { curl -fsS -m 5 "$1" | grep -q "$2"; }

kafka_topics() {
  "${compose[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
}

has_all_topics() {
  local listed
  listed=$(kafka_topics | tr -d '\r') || return 1
  for t in usage.raw usage.archive.control usage.control usage.dlq usage.corrections budget.settle budget.changes gateway.log; do
    grep -qx "$t" <<<"$listed" || { echo "missing topic $t"; return 1; }
  done
}

postgres_schemas() {
  "${compose[@]}" exec -T postgres psql -U billing -d billing -tAc \
    "select count(*) from information_schema.schemata where schema_name in
     ('catalog','metering','rating','balance_ledger','budget_shard','reconciliation','invoicing','payments','usage_view')" |
    tr -d '\r' | grep -qx 9
}

# Connector and every task RUNNING (a connector can run while its tasks have failed).
connector_running() {
  local status
  status=$(curl -fsS -m 5 "http://localhost:8083/connectors/$1/status") || return 1
  grep -qF '"tasks":[{' <<<"$status" || { echo "no tasks"; return 1; }
  if grep -qE '"state":"(FAILED|PAUSED|UNASSIGNED)"' <<<"$status"; then
    echo "$status" | head -c 300
    return 1
  fi
}

flink_has_taskmanager() {
  curl -fsS -m 5 http://localhost:8081/overview | grep -Eq '"taskmanagers":[1-9]'
}

s3_buckets() {
  local listed
  listed=$(echo "s3.bucket.list" | "${compose[@]}" exec -T seaweedfs weed shell -master=localhost:9333) || return 1
  grep -q archive <<<"$listed" && grep -q flink <<<"$listed"
}

echo "Local environment health:"
check "kafka broker and topics" has_all_topics
check "postgres schemas" postgres_schemas
check "flink jobmanager + taskmanager" flink_has_taskmanager
check "seaweedfs s3 buckets (archive, flink)" s3_buckets
check "iceberg rest catalog" http_ok http://localhost:8181/v1/config
check "kafka connect iceberg sink plugin" http_has http://localhost:8083/connector-plugins IcebergSinkConnector
check "usage.raw archive connector and tasks running" connector_running usage-raw-iceberg
check "prometheus" http_ok http://localhost:9090/-/healthy
check "grafana" http_ok http://localhost:3000/api/health
check "toxiproxy" http_ok http://localhost:8474/version

if ((failures > 0)); then
  echo "$failures check(s) failed"
  exit 1
fi
echo "all healthy"
