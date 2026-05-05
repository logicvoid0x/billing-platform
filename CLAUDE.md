# Billing platform — rules for Claude Code

## Source of truth
- Behavior: docs/design.md (unified billing design). Plan: docs/implementation-plan.md.
- Work queue: TASKS.md. Handoff between sessions and pauses: docs/progress/resume.md.
- Decisions: docs/decisions/*.md. Read the ones relevant to your task before coding.

## Build
- Gradle Kotlin DSL; versions only in gradle/libs.versions.toml; never hard-code a version in a module.
- Toolchains: Java 17 for libs:money, libs:domain, libs:contracts, libs:rating and jobs:*; Java 21 everywhere else.
- Never add a dependency from a Java-17 module to a Java-21 module.
- Run ./gradlew build before declaring any task done.

## Money
- Every amount is a long count of cents. Never BigDecimal, BigInteger, double or float, and never int for money.
- Prices are Cents.Rate(cents, perUnits); discounts are int basis points that must give a whole-cent rate.
- Never round per request: keep running quantities per SKU and charge with Cents.charge(before, after, rate).
- These money rules override the money representation in docs/design.md (see docs/decisions/0002-money-in-cents.md).
- Invoice lines are Cents.forTotal of the period total: rounded once, half-even.

## Correctness
- Every write is idempotent on a business key (see the design's idempotency table).
- Keys come from business identity, never from processing time or send-time IDs.
- Ledgers are append-only; corrections are new entries.
- Meter codes are permanent strings, never enum name() or ordinal().

## Working style
- Before each phase, list the files to create and the tests that prove the phase's "Done when".
- Write tests first for money, rating, idempotency and failure behavior.
- Prefer small commits per task.
- If the design is ambiguous, add a question to docs/decisions/open-questions.md, mark the task [!], and move on.

## Continuous task run
These rules apply when the quota guard is running (the run was started with scripts/run-tasks.sh
or /run-tasks, and the status line shows "run: RUNNING").

### Picking and finishing tasks
- Work through TASKS.md top to bottom. Take the first `[~]` task; if there is none, take the first
  `[ ]` task whose "needs" are all `[x]`.
- Mark a task `[~]` when you start it. When its check passes, mark it `[x]`, commit with a message
  that starts with the task ID (for example `P1.3: rating library golden tests`), then take the next task.
- If a task cannot be finished (missing decision, failing external dependency), mark it `[!]`, add one
  indented line saying why, and move on. Never leave the run idle waiting for an answer.
- Do not ask the user questions during a run; record them in docs/decisions/open-questions.md.
- Never edit `.claude/settings.json`, `.claude/hooks/`, or `.claude/quota-guard.properties`, and never
  run `quota-guard.sh cancel` or `reset` yourself.

### After every task
- Overwrite docs/progress/resume.md with: the last finished task ID, the current task ID, what is
  done, what is left, and the exact next step. Keep it under 30 lines.

### When a message says "QUOTA CAP REACHED"
The run has used its share (60%) of the 5-hour usage window. Do this and nothing else:
1. Do not start new work. Only Read, Write, Edit, Grep, Glob and git commands (no push) are
   allowed now; do not run builds or tests.
2. If the last build you ran passed, commit the work; otherwise leave it uncommitted.
3. In TASKS.md mark the current task `[~]` and add one indented status line under it.
4. Overwrite docs/progress/resume.md as described above, including the exact next step.
5. End your turn. The guard holds the session and resumes it automatically after the window resets.

### When a message says the usage window has reset
Read docs/progress/resume.md and TASKS.md, then continue from the exact next step recorded there.
