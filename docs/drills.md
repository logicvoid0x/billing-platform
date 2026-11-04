# Failure drills

Each failure drill, the behaviour the design requires, and the automated test
that exercises it. Run them all with `./gradlew build` (Docker must be running for the
Testcontainers ones).

| Drill | Required behaviour (design) | Test | Status |
|-------|-----------------------------|------|--------|
| Kafka down | Hosts spool to disk; inference never waits; every event arrives exactly once after dedupe; control totals match | `services:inference-sim` KafkaOutageTest (kills and restarts a broker mid-run) | Covered |
| Shard leader loss | A new leader takes over; no hold is lost or applied twice | `services:budget-shard` RatisFailoverTest (3 nodes, the leader is stopped) | Covered |
| Shard unreachable (partition gateway ↔ shard) | Gateways apply each budget's fail policy: fail-open up to `F_a`, else deny; streams stop with `shard_unavailable` | `libs:admission` AdmissionControllerTest (FlakyStore down), StreamBudgetGuardTest; `services:gateway` GrpcBudgetStoreTest (server stopped) | Covered in process; no toxiproxy network partition yet |
| Flink restart | Resumes from a checkpoint; idempotent sinks absorb replayed batches; totals equal the input | `jobs:usage-aggregator` ForcedFailuresTest (three forced failures) | Covered |
| Ledger down | Admission is unaffected (the cross-leg rule: gateways never call the ledger); escrow grants wait and resume exactly once; nothing is lost | `services:balance-ledger` LedgerDownDrillTest (the ledger database switched off and back on) | Covered |

Remaining refinement: a real network partition on the local stack through toxiproxy
(`infra/toxiproxy`), now that the services can run together.
