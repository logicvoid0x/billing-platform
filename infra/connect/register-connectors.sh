#!/usr/bin/env bash
# Registers (or updates) every connector in infra/connect/connectors/ on the Connect worker.
# Idempotent: PUT /connectors/<name>/config creates or replaces. Also creates the Iceberg
# namespace the archive table lives in.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
connect="${CONNECT_URL:-http://localhost:8083}"
catalog="${ICEBERG_REST_URL:-http://localhost:8181}"
out="$(mktemp)"
trap 'rm -f "$out"' EXIT

# Namespace "billing" (409 = already there).
code=$(curl -s -o "$out" -w '%{http_code}' -X POST "$catalog/v1/namespaces" \
  -H 'Content-Type: application/json' -d '{"namespace":["billing"]}')
case "$code" in
  200|409) ;;
  *) echo "creating Iceberg namespace failed: HTTP $code"; cat "$out"; exit 1 ;;
esac

for f in "$here"/connectors/*.json; do
  name=$(basename "$f" .json)
  code=$(curl -s -o "$out" -w '%{http_code}' -X PUT "$connect/connectors/$name/config" \
    -H 'Content-Type: application/json' --data-binary "@$f")
  case "$code" in
    200|201) echo "  connector $name registered" ;;
    *) echo "registering $name failed: HTTP $code"; cat "$out"; exit 1 ;;
  esac
done
