# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A usage-based billing platform for an inference API: admit requests against real-time budgets,
meter and rate usage, invoice and collect. `docs/design.md` is the behavioral spec; its
"Implementation decisions" section records choices that refine it (code comments cite them as `Q2`,
`Q7`, ...) — read the relevant ones before changing an area. `docs/drills.md` maps each failure drill to the test that covers it.

## Commands

```bash
./gradlew build                                   # compile, Spotless check, Error Prone, all tests, JaCoCo
./gradlew spotlessApply                           # fix formatting (google-java-format)
./gradlew :libs:rating:test                       # one module's tests
./gradlew :libs:rating:test --tests '*GoldenRatingTest'            # one class
./gradlew :libs:rating:test --tests '*GoldenRatingTest.someMethod' # one method
```

- Run `./gradlew build` before calling a change done; CI (`.github/workflows/ci.yml`) runs exactly that on JDK 21.
- Docker must be running: modules with the `billing.docker-tests` plugin use Testcontainers, and
  their test tasks are serialized through a shared build service (one at a time).
- Compiler runs with `-Xlint:all -Werror` plus Error Prone, so warnings fail the build.

Local stack (Kafka, Connect → Iceberg on SeaweedFS, Flink, Postgres, Prometheus/Grafana, toxiproxy)
via `infra/docker-compose.yml`:

```bash
make up          # single broker; creates topics, registers connectors, health check
make up-multi    # 3-broker profile
make up-stripe   # Stripe CLI webhook forwarder; needs STRIPE_API_KEY (test mode)
make down        # keep volumes;  make clean  deletes them
```

## Build layout

- Gradle Kotlin DSL. Versions only in `gradle/libs.versions.toml` (pins explained in the design's "Platform and toolchain"); never hard-code a version in a module.
- Convention plugins in `build-logic/src/main/kotlin`: `billing.java-library-17`, `billing.java-library-21`,
  `billing.java-service-21`, `billing.flink-job`, `billing.protobuf`, `billing.connect-plugin`, `billing.docker-tests`.
- Toolchains: Java 17 for `libs:money`, `libs:domain`, `libs:contracts`, `libs:rating` and `jobs:*`
  (they run inside Flink); Java 21 everywhere else. A Java-17 module must never depend on a Java-21 module.
- `libs:money` and `libs:rating` publish test fixtures (`testFixtures(project(...))`): ArchUnit money
  rules (`MoneyRules`, enforced by a `MoneyTypesArchTest` in each money-handling module) and a golden catalog.

## Architecture

Three legs, connected by Kafka and ledgers; only the gateway is on the request path.

1. **Admit** — `services:gateway` admits and streams requests using `libs:admission`, holding
   leases/holds against `services:budget-shard` (Apache Ratis replicated state machine, gRPC).
   Shards receive escrow from `services:balance-ledger` (Postgres, append-only). Gateways never
   call the ledger. `services:inference-sim` stands in for inference hosts.
2. **Meter and rate** — hosts emit `RequestUsageEvent`s to Kafka `usage.raw` (spooling to disk when
   Kafka is down) plus per-hour control totals on `usage.control`. `libs:usage-connect` lets Kafka
   Connect archive `usage.raw` to Iceberg (`billing.usage_raw`), the source of truth for replay.
   `jobs:usage-aggregator` (Flink) dedupes and writes `usage_hourly` (org × SKU × hour) and
   `usage_hourly_by_key` idempotently per checkpoint batch; `jobs:usage-replay` recomputes sealed
   hours from the archive. `services:usage-view` serves `GET /v1/usage`. `services:rating-engine`
   rates hours with `libs:rating` against `services:catalog` (effective-dated, immutable SKUs and
   prices) into a versioned rated ledger. `services:reconciliation` compares gateway logs and host
   control totals against every stage.
3. **Invoice and collect** — `services:invoicing` closes periods into invoice lines (including the
   commitment shortfall line); `services:payments` takes Stripe webhooks and posts top-ups to the
   balance ledger (`POST /v1/top-ups`), and publishes `budget.changes` so shards re-admit topped-up accounts.

Wire contracts are Protobuf in `libs/contracts/src/main/proto/billing/v1` (usage, shard, budget,
ledger, catalog) with codecs to the `libs:domain` records. HTTP ports: catalog 8090, payments 8085,
balance-ledger 8086, usage-view 8087.

## Money rules (docs/design.md, "Money representation")

- Every amount is a `long` count of cents (`Cents` in `libs:money`); Postgres `BIGINT`, Protobuf `int64`.
  Never `BigDecimal`, `BigInteger`, `double`, `float`, or `int` for money.
- Prices are `Cents.Rate(cents, perUnits)`; discounts are `int` basis points that must give a whole-cent rate.
- Never round per request: keep running quantities per SKU and charge with `Cents.charge(before, after, rate)`.
- Invoice lines are `Cents.forTotal` of the period total: rounded once, half-even.

## Correctness rules

- Every write is idempotent on a business key (table in docs/design.md, "Idempotency, end to end").
  Keys come from business identity, never from processing time or send-time IDs.
- Ledgers are append-only; corrections are new entries. Catalog rows are immutable except closing an
  open period (design: "Catalog immutability").
- Meter codes are permanent strings, never enum `name()` or `ordinal()`.
- Write tests first for money, rating, idempotency and failure behavior.

## Compatibility notes

- Changing the aggregator's keyed state or window key means it must start fresh, not from an old savepoint.
- Flyway migrations (`db/migration/**`) are immutable once applied: never edit one, even a comment
  (it changes the checksum); add a new version.
- `BudgetState` snapshot format is versioned (currently 5); bump it on change and expect old Ratis storage to be reformatted.
- When the design is ambiguous, ask rather than guess, and record the answer in the design's
  "Implementation decisions" section.
