# Failure drills

Each drill from the plan (Phase 15), the behaviour the design requires, and the automated test
that exercises it. Run them all with `./gradlew build` (Docker must be running for the
Testcontainers ones).

| Drill | Required behaviour (design) | Test | Status |
|-------|-----------------------------|------|--------|
| Kafka down | Hosts spool to disk; inference never waits; every event arrives exactly once after dedupe; control totals match | `services:inference-sim` KafkaOutageTest (kills and restarts a broker mid-run) | Covered |
| Shard leader loss | A new leader takes over; no hold is lost or applied twice | `services:budget-shard` RatisFailoverTest (3 nodes, the leader is stopped) | Covered |
| Shard unreachable (partition gateway ↔ shard) | Gateways apply each budget's fail policy: fail-open up to `F_a`, else deny; streams stop with `shard_unavailable` | `libs:admission` AdmissionControllerTest (FlakyStore down), StreamBudgetGuardTest; `services:gateway` GrpcBudgetStoreTest (server stopped) | Covered in process; no toxiproxy network partition yet |
| Flink restart | Resumes from a checkpoint; idempotent sinks absorb replayed batches; totals equal the input | `jobs:usage-aggregator` ForcedFailuresTest (three forced failures) | Covered |
| Ledger down | Admission is unaffected (the cross-leg rule); escrow grants and true-ups wait; nothing is lost | none yet | **Gap**: needs a test that the gateway keeps admitting from leases and escrow while the balance ledger is unreachable, and that grants resume afterwards |

Gaps to close: a toxiproxy partition drill on the local stack (`infra/toxiproxy`), and the ledger-down
drill. Both need the services wired together, which waits on open questions Q7 and Q10.
