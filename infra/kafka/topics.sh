#!/usr/bin/env bash
# Creates every Kafka topic of the billing platform. Idempotent: existing topics are left alone.
#
#   infra/kafka/topics.sh              single-broker profile: replication 1, min.insync.replicas 1
#   infra/kafka/topics.sh --multi      multi-broker profile:  replication 3, min.insync.replicas 2
#   DRY_RUN=1 infra/kafka/topics.sh    print the commands instead of running them
#
# Runs kafka-topics.sh from $KAFKA_HOME if set, otherwise inside the compose broker container.
set -euo pipefail
# Git Bash on Windows rewrites absolute container paths (/opt/...) passed to docker; stop that.
export MSYS_NO_PATHCONV=1

# pwd -W: a Windows path under Git Bash, where docker gets paths unconverted (MSYS_NO_PATHCONV).
here="$(cd "$(dirname "$0")" && (pwd -W 2>/dev/null || pwd))"
compose_file="$here/../docker-compose.yml"

profile=single
if [[ "${1:-}" == "--multi" ]]; then
  profile=multi
fi

if [[ "$profile" == multi ]]; then
  replication=3
  min_isr=2
  service=kafka-1
else
  replication=1
  min_isr=1
  service=kafka
fi
bootstrap="${BOOTSTRAP:-localhost:9092}"

DAY_MS=86400000

# name | partitions | extra configs (comma-separated) | key and purpose
topics=(
  "usage.raw|24|retention.ms=$((14 * DAY_MS))|key org_id; RequestUsage facts, archived to Iceberg"
  "usage.archive.control|1|retention.ms=$((7 * DAY_MS))|Iceberg sink commit coordination, not business data"
  "usage.control|6|retention.ms=$((14 * DAY_MS))|key host_id; per-host control totals"
  "usage.dlq|6|retention.ms=$((30 * DAY_MS))|key org_id; malformed or unresolvable usage"
  "usage.corrections|12|retention.ms=$((30 * DAY_MS))|key org_id; usage later than allowed lateness"
  "budget.settle|24|retention.ms=$((14 * DAY_MS))|key request_id; gateway settle records"
  "budget.changes|12|cleanup.policy=compact,min.compaction.lag.ms=60000|key budget_id; modes, caps, fail policies"
  "gateway.log|24|retention.ms=$((14 * DAY_MS))|key request_id; customer-visible usage per request"
)

kafka_topics() {
  if [[ -n "${KAFKA_HOME:-}" ]]; then
    "$KAFKA_HOME/bin/kafka-topics.sh" "$@"
  else
    docker compose -f "$compose_file" exec -T "$service" /opt/kafka/bin/kafka-topics.sh "$@"
  fi
}

run() {
  if [[ "${DRY_RUN:-0}" == 1 ]]; then
    printf 'kafka-topics.sh'
    printf ' %q' "$@"
    printf '\n'
  else
    kafka_topics "$@"
  fi
}

echo "Creating topics (profile=$profile, replication=$replication, min.insync.replicas=$min_isr)"
for spec in "${topics[@]}"; do
  IFS='|' read -r name partitions extra purpose <<<"$spec"
  args=(--bootstrap-server "$bootstrap" --create --if-not-exists
    --topic "$name" --partitions "$partitions" --replication-factor "$replication"
    --config "min.insync.replicas=$min_isr")
  IFS=',' read -r -a configs <<<"$extra"
  for c in "${configs[@]}"; do
    args+=(--config "$c")
  done
  echo "  $name ($purpose)"
  run "${args[@]}"
done

if [[ "${DRY_RUN:-0}" != 1 ]]; then
  echo "Topics now present:"
  kafka_topics --bootstrap-server "$bootstrap" --list
fi
