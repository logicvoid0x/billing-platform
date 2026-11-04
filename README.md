# Billing platform

Usage-based billing for an inference API. It admits each request against real-time prepaid
balances and spend limits, meters and rates the usage, closes periods into invoice lines, and
collects through Stripe.

- **Design (the behavioral spec):** [docs/design.md](docs/design.md). Its "Implementation
  decisions" section records the choices made while building it. Code comments cite those choices
  as `Q2`, `Q7` and so on.
- **Failure drills and the tests that cover them:** [docs/drills.md](docs/drills.md)
- **Rules for AI-assisted work:** [CLAUDE.md](CLAUDE.md)

## How it works

The API gateway is the only component on a request's path. Everything else moves credit, usage or
money in bulk, off the hot path.

```text
            ┌───────── 1 · Admit ─────────┐
Customer ─► │ gateway ◄─leases─► budget   │ ◄─escrow / rollups─► balance ledger ◄─ top-ups ─ payments ◄─ Stripe
            │   │                shard    │                         ▲
            └───┼─────────────────────────┘                         │ hourly true-up
                ▼                                                   │
        inference hosts ── usage.raw ──► Flink aggregator ──► usage_hourly ──► rating engine ──► rated ledger
                              │                                    │                               │
                              ▼                                    ▼                               ▼
                      Iceberg raw archive ──replay──►        usage view                       invoicing ──► Stripe
            (2 · Meter and rate)                                                   (3 · Invoice and collect)
```

| Leg | Modules | What happens |
|---|---|---|
| **Admit** | `services:gateway`, `libs:admission`, `services:budget-shard`, `services:balance-ledger` | The gateway holds against every budget that applies (all or nothing), using leases escrowed in advance from a Ratis-replicated shard. The shard gets escrow from the append-only balance ledger. Gateways never call the ledger. |
| **Meter** | `services:inference-sim`, `libs:usage-connect`, `jobs:usage-aggregator`, `jobs:usage-replay` | Hosts send usage events to Kafka (and spool to disk if Kafka is down), plus per-hour control totals. Kafka Connect archives `usage.raw` to Iceberg. Flink dedupes the events and writes `usage_hourly` exactly once per checkpoint. Replay recomputes sealed hours from the archive. |
| **Rate** | `libs:rating`, `services:catalog`, `services:rating-engine` | Effective-dated, immutable catalog. Hours are rated on per-SKU running totals into a versioned, append-only rated ledger. |
| **Invoice and collect** | `services:invoicing`, `services:payments` | Period close produces invoice lines, rounded once. Stripe webhooks become idempotent top-ups and publish `budget.changes`, so blocked accounts are admitted again. |
| **Check** | `services:reconciliation`, `services:usage-view` | Completeness checks (gateway logs and host totals against every stage) and checks against Stripe. Usage API and dashboards. |

### Rules that hold everywhere

- **Money is a `long` count of cents** (`Cents` in `libs:money`). Never `BigDecimal`, `double`,
  `float` or `int`. Prices are `Cents.Rate(cents, perUnits)`. Charges are
  `Cents.charge(before, after, rate)` on running totals, never rounded per request. ArchUnit tests
  in every money module enforce this.
- **Every write is idempotent on a business key**, never on processing time. See the design's
  "Idempotency, end to end" table.
- **Ledgers and the catalog are append-only.** Corrections are new rows.
- **Meter codes are permanent strings.** Never use an enum's `name()` or `ordinal()`.

## Repository layout

| Path | Contents |
|---|---|
| `libs/money` | `Cents`, the money type, and its ArchUnit rules (test fixtures) |
| `libs/domain` | Domain records: budgets, leases, usage |
| `libs/contracts` | Protobuf wire contracts (`src/main/proto/billing/v1`), codecs, `HttpCatalogClient` |
| `libs/rating` | Rating library: SKU resolution, pricing, tiers. Its test fixtures include a golden catalog. |
| `libs/admission` | Admission control and stream budget guard used by the gateway |
| `libs/usage-connect` | Kafka Connect converter for the protobuf `usage.raw` (shaded plugin jar) |
| `libs/testing` | Cross-cutting checks, e.g. every SLO signal in the design has a Prometheus alert (`infra/prometheus/alerts.yml`) |
| `services/*` | Spring Boot services (one Postgres schema each, migrated by Flyway) |
| `jobs/*` | Flink jobs (shadow jars) |
| `build-logic/` | Gradle convention plugins (`billing.*`) |
| `gradle/libs.versions.toml` | Every dependency version, and nowhere else |
| `infra/` | Docker Compose stack, Kafka topics, Connect, Postgres init, Prometheus/Grafana, toxiproxy |

## Prerequisites

- **JDK 21** to run Gradle. The Java 17 toolchain for the Flink-bound modules is downloaded
  automatically (foojay).
- **Docker** (Docker Desktop on Windows/macOS). It's needed for the local stack and for the
  Testcontainers tests that run in `./gradlew build`.
- **GNU make and bash.** On Windows use Git Bash; the scripts handle its path rewriting.
- Optional: a **Stripe test-mode key** (`STRIPE_API_KEY`) for the live webhook forwarder.

## Build and test

```bash
./gradlew build                     # compile, format check, Error Prone, all tests, coverage
./gradlew spotlessApply             # fix formatting (google-java-format)

./gradlew :libs:rating:test                                    # one module
./gradlew :libs:rating:test --tests '*GoldenRatingTest'        # one class
./gradlew :libs:rating:test --tests '*GoldenRatingTest.name'   # one method
```

- Compiler warnings fail the build (`-Xlint:all -Werror` plus Error Prone).
- Modules that start containers (the `billing.docker-tests` plugin) run their tests one module at
  a time, so Docker isn't overloaded.
- Coverage reports: `<module>/build/reports/jacoco/test/html/index.html`.
- CI (`.github/workflows/ci.yml`) runs `./gradlew build` on JDK 21 for pushes to `main` and for
  pull requests.

### Toolchains

`libs:money`, `libs:domain`, `libs:contracts`, `libs:rating` and `jobs:*` target **Java 17**
because they run inside Flink. Everything else targets **Java 21**. A Java 17 module must never
depend on a Java 21 module.

## Local environment

```bash
make up          # build Connect plugin, start stack, create topics, register connectors, health check
make up-multi    # same with 3 Kafka brokers (replication 3, min.insync.replicas 2)
make up-stripe   # add the Stripe CLI webhook forwarder (needs STRIPE_API_KEY)
make health      # re-run the health check
make ps | logs   # status / follow logs
make down        # stop, keep data
make clean       # stop and delete all local data
```

| Component | Address |
|---|---|
| Kafka (from host) | `localhost:19092` (multi-broker: `29092`–`29094`) |
| Postgres | `localhost:5432`, database `billing`, user/password `billing` (local defaults) |
| Flink UI | http://localhost:8081 |
| Kafka Connect | http://localhost:8083 |
| Iceberg REST catalog | http://localhost:8181 |
| SeaweedFS S3 | http://localhost:8333 (buckets `archive`, `flink`) |
| Prometheus / Grafana | http://localhost:9090 / http://localhost:3000 (anonymous viewer) |
| Toxiproxy | API `8474`; Kafka via `19093`, Postgres via `15432` |

Kafka topics (created by `infra/kafka/topics.sh`):

| Topic | Key | Purpose |
|---|---|---|
| `usage.raw` | org_id | Request usage facts; archived to Iceberg `billing.usage_raw` |
| `usage.control` | host_id | Per-host, per-hour control totals for reconciliation |
| `usage.dlq` | org_id | Malformed or unresolvable usage (tracked as unbilled) |
| `usage.corrections` | org_id | Usage later than the allowed lateness |
| `budget.settle` | request_id | Gateway settle records |
| `budget.changes` | budget_id | Compacted; budget modes, caps, fail policies, top-ups |
| `gateway.log` | request_id | Customer-visible usage per request |
| `usage.archive.control` | — | Iceberg sink commit coordination |

## Running services

Each service is a Spring Boot app:

```bash
./gradlew :services:catalog:bootRun
```

| Service | HTTP port | Notes |
|---|---|---|
| `catalog` | 8090 | `GET /v1/catalog/snapshot?hour=`, `GET /v1/catalog/version` |
| `payments` | 8085 | Stripe webhooks. Off until `payments.enabled=true`; needs `STRIPE_WEBHOOK_SECRET`. |
| `balance-ledger` | 8086 | `POST /v1/top-ups` |
| `usage-view` | 8087 | `GET /v1/usage` |
| `gateway` | — | Off until `gateway.enabled=true`; talks to the shard (`gateway.shard-target`, default `localhost:9099`) and the catalog |
| `inference-sim` | — | Simulated inference hosts emitting usage; exposes `/actuator/prometheus` |

The services connect to the local stack's defaults (`localhost:5432`, `localhost:19092`); override
them with the usual Spring properties. The rating engine has no scheduled deployment wiring yet;
it is exercised through its tests.

### Flink jobs

Build the shadow jar (`./gradlew :jobs:usage-aggregator:shadowJar`) and submit it to the Flink
cluster. Both jobs read the database password from `METERING_DB_PASSWORD`.

```text
UsageAggregatorJob <bootstrap-servers> <catalog-url> <metering-jdbc-url> <db-user>
UsageReplayJob     <replay-id> <catalog-url> <metering-jdbc-url> <db-user> <iceberg-properties> <hour>...
```

`infra/replay/iceberg-local.properties` points replay at the local archive. Replay refuses hours
still inside the allowed lateness.

## Making changes

- **Versions** go only in `gradle/libs.versions.toml`.
- **Flyway migrations** (`src/main/resources/db/migration/**`) are immutable once applied. Add a
  new version and never edit an old one, not even a comment, because that changes its checksum.
- **Stateful formats:** changing the aggregator's keyed state or window key means starting it fresh
  (not from a savepoint). Changing the `BudgetState` snapshot format means bumping its version
  (currently 5), and old Ratis storage must be reformatted.
- **Tests first** for money, rating, idempotency and failure behavior. The golden catalog and cases
  in `libs:rating` test fixtures are the shared reference.
- **Design gaps:** decide them explicitly and record the outcome in the design's "Implementation
  decisions" section.
