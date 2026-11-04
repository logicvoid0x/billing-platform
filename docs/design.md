# Billing & Payment Collection Platform — Unified End-to-End Design

2026-10-31 · Rakesh

## Purpose and scope

This platform turns every Claude API request into collected cash in three stages: **admit** it against the customer's credit and limits, **meter and rate** what it used, then **invoice and collect**. It merges two existing designs into one workflow, resolves the points where they meet, and leaves deep detail in the source docs.

| Leg                         | Source design                                                                                                     | What it owns                                                                                       |
|-----------------------------|-------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------|
| Real-time enforcement       | [Prepaid Balance & Spend-Limit Enforcement](https://claude.ai/code/artifact/176f0567-e43b-495b-9b2f-da00c97741da) | Admitting or denying each request against prepaid balances, spend limits and plan usage allowances |
| Metering, rating, invoicing | [Usage Metering & Billing Pipeline](https://claude.ai/code/artifact/8f547ae1-c31f-48ff-8e97-0648ec5540e8)         | Exactly-once usage capture, rating against prices and contracts, invoice lines to Stripe           |
| Integrations                | Billing Integration Platform                                                                                      | Reserved; to be merged later (see the reserved section)                                            |

One rule divides the work: enforcement is authoritative for **whether a request may run**, and the rated ledger is authoritative for **what is owed**. Every seam in this doc exists to keep those two answers in agreement.

**Out of scope:** subscription fees for consumer plans (their usage allowances are in scope), fraud scoring (consumed only as a policy input), and per-minute rate limits, which stay in a separate token-bucket limiter in the same gateway hook.

## End-to-end workflow

A request's life runs on four clocks: milliseconds to admit it, seconds to meter it, an hour to rate and reconcile it, and a billing period to invoice and collect it. One **request ID**, minted once by the API gateway, ties every step together.

1.  **Admit (per request, under 1 ms; 1–5 ms near \$0).** The gateway mints the request ID, tokenizes the prompt and runs the per-minute limiter. It then takes a hold on every budget that applies, all or nothing: plan allowances first, then the prepaid balance and spend limits if extra usage is on. Most holds come from an in-process lease; accounts near \$0 make one in-region call to the regional budget shard.

2.  **Run and checkpoint (while streaming).** The gateway proxies the stream and counts output tokens. At 75% of the current chunk it takes the next chunk's hold. If that hold cannot be funded, it signals the inference server to stop with a budget-exhausted reason.

3.  **Settle (at completion).** The gateway prices the actual usage with the shared rating library, releases unused hold, debits any shortfall and publishes a settle record keyed by the request ID.

4.  **Emit usage (at completion).** The inference host emits one raw usage event of facts only (tokens by meter, model, tier, event time) to Kafka. If Kafka is down it spools to disk; inference never waits on billing.

5.  **Meter (every 15–30 s).** One Flink job dedupes, resolves SKUs and writes per-checkpoint batches into `usage_hourly`. Dashboards and spend alerts read it within 60 s.

6.  **Roll up credit (every 5 s) and true up (hourly).** Regional shards post usage rollups to the account's home balance ledger. Hourly, the rated amount is compared with what the balance recognized, and the gap is posted as a `TRUE_UP`.

7.  **Rate (hourly, re-ratable).** The rating engine prices each `(org, SKU, hour)` row against the price book and contract overrides and appends it to the rated ledger with every version used.

8.  **Close and invoice (period close + 24 h).** The period freezes, amounts round once per invoice line, and lines are pushed to Stripe with idempotency keys. Later usage becomes a prior-period adjustment.

9.  **Collect (Stripe).** Postpaid invoices are charged and dunned in Stripe. Prepaid top-ups and auto-recharge are charged there too; the payment webhook writes a `TOP_UP`, and gateways drop a cached denial within 5 s.

### How each customer type moves through it

| Stage                        | Prepaid                                                | Postpaid                          | Plan subscriber                                                                      |
|------------------------------|--------------------------------------------------------|-----------------------------------|--------------------------------------------------------------------------------------|
| Budgets checked at admission | Org balance, plus any spend limits                     | Spend limits, if set              | Usage windows (5-hour, weekly, monthly); balance and limits too if extra usage is on |
| When a budget runs out       | Deny new requests; stop streams at the next checkpoint | Deny at the limit                 | Deny with a `resets_at` time, or overflow to paid usage                              |
| What the period produces     | Rated usage drawn down from credit                     | Invoice charged through Stripe    | Overflow usage only, billed as prepaid or postpaid                                   |
| When cash arrives            | Before use: top-up or auto-recharge                    | After the period: invoice payment | Plan fee (out of scope); overflow as above                                           |
| Negative balance             | Bounded overdraft, carried into the next top-up        | Not applicable                    | Not applicable                                                                       |

## Requirements

The platform must be correct first, auditable second and fast third, and a billing failure must never take down inference for a healthy account.

**Functional, by stage**

- **Admit:** check each request against every budget that applies (balance, org, workspace and key limits, user and org usage windows) in one step; hold, extend and settle credit; stop a stream cleanly when its next checkpoint cannot be funded; on denial, say which budget ran out and when it resets.

- **Meter:** capture usage for every billable request, including streams cancelled midway (bill tokens actually produced); resolve usage to SKUs; aggregate per org, SKU and hour.

- **Rate and invoice:** rate against an effective-dated price book plus contract overrides, volume tiers, credits and committed spend; produce invoice lines at period close; support corrections and re-rating with a full audit trail.

- **Collect:** take top-ups and auto-recharge for prepaid accounts, and collect postpaid invoices with tax and dunning, through Stripe.

- **Show:** expose live balance, holds and window usage (percent used, resets at) within seconds, and metered usage within a minute.

**Non-functional**

| Property                      | Target                                                                               | Owning leg               |
|-------------------------------|--------------------------------------------------------------------------------------|--------------------------|
| Impact on inference           | None: billing outages delay data or apply a fail policy, never fail healthy accounts | Both                     |
| Added admission latency       | p99 under 1 ms for most traffic; under 5 ms for near-\$0 accounts                    | Enforcement              |
| Overdraft per account         | At most `O_max(a)`, a stated formula (see Stage 2)                                   | Enforcement              |
| Top-up to resume              | p99 under 5 s from payment confirmation                                              | Enforcement + collection |
| Usage freshness for customers | p99 under 60 s                                                                       | Metering                 |
| Loss or duplication           | Zero after period close, proven by reconciliation                                    | Metering                 |
| Invoice-grade data            | Final at period close + 24 h grace                                                   | Rating                   |
| Real-time vs rated drift      | Under 0.1% per account-hour; zero after true-up                                      | Seam                     |
| Raw data retention            | 7+ years, immutable                                                                  | Metering                 |

**Scale (illustrative, to size the design):** 100,000 requests/s at peak across 5–10 regions; about 1 KB per raw event (about 100 MB/s ingest, 8–9 billion events a day, about 4 metered lines per request); millions of accounts, heavy-tailed, with the largest at about 10% of traffic; about \$0.01 per request, or about \$1,000/s of platform-wide spend.

## Architecture

The API gateway is the only component on every request's path; everything else moves credit, usage or money in bulk, off the hot path.

The diagram in the original doc shows these components and flows (text version for this repository copy):

```text
Customer (user or app) --request--> API gateway --streamed response--> Customer

1 · Admit
  Home balance ledger <--escrow / usage rollups--> Regional budget shard <--leases / holds--> API gateway
  API gateway <--prompt / tokens, stop--> Inference servers

2 · Meter and rate
  Inference servers --usage event--> Kafka usage.raw (partitioned by org_id, acks=all, idempotent)
  Kafka usage.raw --> Raw archive (S3 / Iceberg, immutable, source of truth for replay)
  Kafka usage.raw --> Flink aggregator (validate, resolve SKUs, dedupe, one batch per checkpoint)
  Flink aggregator --> Dead-letter queue (malformed or unknown SKU, tracked as unbilled)
  Flink aggregator --batches--> usage_hourly (org x SKU x hour totals)
  usage_hourly --> Real-time usage view (dashboards, spend alerts, ~60 s fresh)
  usage_hourly --> Rating engine <-- Catalogs (SKUs, context-band rules, price book, contracts)
  Rating engine --> Rated ledger (append-only, versioned, corrections as new rows)
  Reconciliation (gateway logs + host totals vs every stage) - - checks - -> Rated ledger
  Rated ledger - - hourly true-up - -> Home balance ledger

3 · Invoice and collect
  Rated ledger --rated rows--> Period close (close + 24 h grace, invoice lines)
  Period close --> Stripe (invoices, charges, tax, dunning, auto-recharge)
  Stripe --top-ups via webhooks--> Home balance ledger
```

The customer's request enters at the gateway, which admits it and streams the response back. From there, read it as a loop: escrow flows from the balance ledger down to gateway leases, usage flows back through metering and rating to invoices, and payments return to the ledger as top-ups. The dashed line is the hourly true-up that keeps every balance equal to the rated ledger.

## Stage 1 — Usage capture and metering

Usage flows one way, from inference host to `usage_hourly`, through stages that are each idempotent and each rebuildable from an immutable raw archive. Full detail: [Usage Metering & Billing Pipeline](https://claude.ai/code/artifact/8f547ae1-c31f-48ff-8e97-0648ec5540e8).

| Step           | Mechanism                                                                                                                                                      | Why                                                                            |
|----------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------|
| Raw event      | One `RequestUsage` per request from the inference host: IDs, model, service tier, event time and token counts by meter; no prices or SKUs                      | Facts survive any later pricing or resolution bug                              |
| Who emits      | Only the attempt that delivered output, under the original request ID; a client retry is a new request and is billed                                           | Inference ran again, so usage is real                                          |
| Transport      | Kafka `usage.raw` keyed by org; `acks=all`, replication 3, `min.insync.replicas=2`, idempotent producer; spool to local disk when Kafka is down                | No silent loss; retries are not written twice                                  |
| Dedupe         | Flink keyed state on `requestId:meterCode` with a 7-day TTL; meter codes are permanent strings                                                                 | Catches resends after a host restart, which producer idempotence cannot        |
| SKU resolution | One metered line per non-zero meter; SKU = meter + every price-changing dimension (model, tier, context band)                                                  | Hourly sums cannot be split back into requests, so the band must be in the key |
| Aggregation    | `keyBy(org, sku, hour)`; each sink subtask writes one batch per checkpoint into `usage_hourly`, with its batch ID in `applied_batches` in the same transaction | Exactly-once totals at the target rate                                         |
| Time           | Event time; 1 h allowed lateness; later events become correction records; a period freezes at close + 24 h                                                     | Late data lands in the right hour or is adjusted, never dropped                |
| Archive        | Kafka → S3 / Iceberg, the source of truth; a replay rebuilds sealed hours into `usage_hourly_rebuild` and swaps them in                                        | Every derived table can be rebuilt after a bug                                 |

The real-time usage view reads the same `usage_hourly` table as invoicing, so dashboards and invoices share one source; freshness is bounded by the 15–30 s checkpoint interval. This stage is never on the admission path: a metering outage delays dashboards and true-ups, but does not change whether a request is admitted.

## Stage 2 — Real-time balance and spend-limit enforcement

The gateway admits from credit that was **escrowed to it in advance**, so the hot path checks an in-memory counter, never a database. Precision is bought only near \$0, and overdraft is bounded by construction. Full detail: [Prepaid Balance & Spend-Limit Enforcement](https://claude.ai/code/artifact/176f0567-e43b-495b-9b2f-da00c97741da).

The metering pipeline cannot do this job: at 15–60 s freshness, the largest account (about \$100/s) could overshoot by about \$6,000.

### One abstraction: the budget

A prepaid balance, a spend limit and a plan usage allowance are all a **budget**, so one hot path, one shard key and one reconciliation serve them all.

| Budget kind                           | Unit                 | Credit comes from                                     | Comes back by                                |
|---------------------------------------|----------------------|-------------------------------------------------------|----------------------------------------------|
| Prepaid balance                       | Cents                | Ledger: top-ups minus debits minus outstanding escrow | Top-up or auto-recharge                      |
| Spend limit (org, workspace, API key) | Cents                | Cap minus spend in the period                         | A new period (a new budget instance)         |
| Usage allowance (user or org pool)    | Weighted usage units | Cap minus usage in the current window                 | Window reset, or usage ageing out if rolling |

Weighted usage units are tokens × a per-SKU weight from an effective-dated **allowance weight book**, so the same SKU resolution and rating library serve dollars and units. Budgets are checked in priority groups: plan allowances first, then money budgets if the customer enabled extra usage.

### Escrow tree and adaptive modes

Credit flows down three levels, and a node can only spend what its parent escrowed to it, so failures can strand credit but never create it.

- **Home balance ledger** (durable, per account) holds the authoritative balance and grants each region an escrow sized at about 30 s of regional burn.

- **Regional budget shard** (in-memory, Raft-replicated, sharded by org) grants gateway leases and serves direct reservations. All of an org's budgets share one shard, so a multi-budget hold is one atomic operation.

- **Gateway-host lease** (in-process, about 2 s of host burn, 2 s TTL) is spent with a compare-and-set. Each org is routed to a cell of 8–32 hosts per region.

| Mode     | When                                                       | Hot-path cost                      |
|----------|------------------------------------------------------------|------------------------------------|
| `LEASE`  | Runway (seconds of credit at current burn) over 60 s       | In-process, about 100 ns           |
| `DIRECT` | Runway under 60 s, small or idle accounts, or a lease miss | One in-region call, p99 under 5 ms |
| `DENY`   | Spendable credit below the smallest possible hold          | Cached reject, no call             |

Switching back to `LEASE` needs 120 s of runway, which prevents flapping. In other regions, escrow is requested in proportion to burn; when an account nears \$0 the home region stops granting new escrow, and only near-\$0 accounts ever pay a cross-region call (about 50–100 ms).

### Bounded overdraft

Overdraft comes only from cost incurred before it was held. Per account it is bounded by:

``` latex
O_{max}(a) = C_a \cdot \delta + F_a + R_a
```

`C_a` is the org's concurrency limit, `δ` the most one request can incur beyond its holds (about one chunk), `F_a` the fail-open allowance, and `R_a` price drift between hold and final rating. With a concurrency limit of 50, 2,048-token chunks at \$15 per million output tokens and a \$20 fail-open allowance, the worst case is about \$21.50 (illustrative prices). A negative balance is carried into the next top-up, and Finance and Legal own the value of `F_a`.

**Fail policy is per account:** fail-open up to `F_a` for accounts with payment history; fail-closed for new accounts, accounts flagged by fraud scoring, and free-credit-only accounts. Usage allowances default to fail-open, because their overshoot costs compute, not cash.

## Stage 3 — Rating, invoicing and payment collection

Rating is ours because contracts are complex; invoicing mechanics, payment, tax and dunning are commodity and go to Stripe. Price changes are data changes, and any period can be re-rated without touching recorded usage.

### Rating

For each `(org, SKU, hour)` row, the rating engine looks up the price in effect at that hour (contract override, else list price), applies discounts and tiers, charges the hour as `F(after) − F(before)` on the SKU's running period quantity (whole cents, see [Money representation](#money-representation)), and appends a rated row recording the price book, contract and rating-code versions. Price effective dates align to hour boundaries, so each row has exactly one price.

*Example:* Acme has 20% off Sonnet 4.6 output tokens and uses 40M in one hour. List \$15.00/M (`Rate(1500, 1_000_000)`) becomes \$12.00/M (`Rate(1200, 1_000_000)`), so the row is 48,000¢, rated with price book v12, contract v3 and rating code v7.

- **Volume tiers** are graduated by default. Each increment is charged `F(after) − F(before)` from a running counter in Flink state, so charges telescope to `F(final)` regardless of arrival order; at close `F(final) − Σ charges` must be zero.

- **Committed-use discounts** replace tier state with commit drawdown.

- **Credit grants** (promotional or purchased) are sub-balances with an expiry, drawn down expiring-first; expiry posts `GRANT_EXPIRY` to breakage revenue.

- **Re-rating** never overwrites: a new `rating_version` is a new key, and the difference is posted as an adjustment.

The same rating library prices enforcement holds, settlement and invoices, so the live balance and the invoice agree by construction and any drift is a bug signal.

### Period close and invoicing

1.  The period freezes at close + 24 h; anything later becomes a prior-period adjustment on the next invoice.

2.  Amounts round once per invoice line, `HALF_EVEN`: each line is `Cents.forTotal` of the period quantity (or `F(total)` for tiers) under a versioned rounding policy signed off by Finance, and must equal the sum of that line's hourly rated increments.

3.  Lines go to Stripe as hourly or daily aggregates per subscription item, never raw events, keyed by `hash(org, sku, period, ledger_version)`. Pushed state lives in our own table, since Stripe prunes idempotency keys after about 24 h.

4.  Every line must be acknowledged before the invoice finalizes, and invoices are generated within 24 h of the grace window ending.

### Collection paths

| Path                       | Trigger                                               | Ledger effect                                                                                 | Enforcement effect                                               |
|----------------------------|-------------------------------------------------------|-----------------------------------------------------------------------------------------------|------------------------------------------------------------------|
| Top-up (prepaid)           | Customer pays; Stripe confirms                        | `TOP_UP`, idempotent on the payment ID                                                        | Home shard recomputes the mode; cached `DENY` drops within 5 s   |
| Auto-recharge (prepaid)    | Spendable credit falls below the customer's threshold | `TOP_UP` when the async charge succeeds (key: org ID + recharge sequence)                     | None needed: if the charge fails, the account spends down to \$0 |
| Postpaid invoice           | Period close                                          | Rated lines invoiced; Stripe collects, taxes and dunns                                        | Spend limits keep enforcing during the period                    |
| Negative balance (prepaid) | Bounded overdraft at \$0                              | Carried; repaid from the next top-up; small abandoned amounts written off as a finance metric | Account stays in `DENY` until topped up                          |

A recharge threshold of at least a few minutes of burn keeps heavy accounts out of `DIRECT` mode entirely, so it is both a product setting and a performance lever.

**New in this unified design (proposed):**

- **Prepaid period statement.** A prepaid org still gets a period document built from the rated ledger: rated lines, then credit applied from the balance ledger. Amount due is zero unless a negative balance remains.

- **Collection status feeds enforcement.** A postpaid org whose invoice goes past due in Stripe can be moved to a fail-closed policy or given a spend limit. The trigger and grace period are open questions for Finance.

## Unified data model and ledgers

The platform keeps **two ledgers with one truth**: the rated ledger says what is owed, the balance ledger says where the customer's credit stands, and true-ups force them to agree at period close.

| Entity                            | Owner          | Key                                                              | Mutability                                    | Read by                                                            |
|-----------------------------------|----------------|------------------------------------------------------------------|-----------------------------------------------|--------------------------------------------------------------------|
| Request ID                        | API gateway    | Minted once per request                                          | Immutable                                     | Every stage: reservation ID, event-ID root, dedupe, reconciliation |
| `RequestUsage` raw event          | Inference host | `requestId`                                                      | Immutable; archived 7+ years                  | Metering, reconciliation                                           |
| SKU catalog                       | Metering       | Opaque `sku_id` plus a readable key                              | Never edited; effective-dated                 | Rating library (holds, settlement, invoices, weight book)          |
| Price book and contract overrides | Rating         | `(sku_id, dates)`; `(org_id, sku_id, dates)`                     | Effective-dated                               | Rating library                                                     |
| Allowance weight book             | Enforcement    | `(sku_id, dates)`                                                | Effective-dated                               | Rating library, in usage units                                     |
| Budget                            | Enforcement    | e.g. `org_42:balance`, `ws_7:limit:2026-11`, `user_9:5h:<start>` | A new instance per period or window           | Regional shard, gateway                                            |
| `usage_windows`                   | Enforcement    | Budget instance                                                  | Snapshotted from the shard's Raft log         | Usage page                                                         |
| `usage_hourly`                    | Metering       | `(org, sku, hour)`                                               | Batch upserts; replay overwrites sealed hours | Rating, dashboards, reconciliation                                 |
| Rated ledger                      | Rating         | `(org, sku, hour, rating_version)`                               | Append-only                                   | Invoicing, true-up                                                 |
| Balance ledger                    | Enforcement    | One entry per movement                                           | Double-entry, append-only                     | Escrow grants, live balance, statements                            |
| Stripe pushed state               | Invoicing      | `hash(org, sku, period, ledger_version)`                         | Append-only                                   | Stripe retries, reconciliation                                     |

### Balance ledger entries

| Entry           | Debit                    | Credit              | Written by                                      |
|-----------------|--------------------------|---------------------|-------------------------------------------------|
| `TOP_UP`        | Cash clearing            | Customer credit     | Payment webhook, idempotent on payment ID       |
| `ESCROW_GRANT`  | Customer credit          | Escrow for region r | Home shard, when a region asks for credit       |
| `ESCROW_RETURN` | Escrow for region r      | Customer credit     | Region returning idle escrow (after 5 min idle) |
| `USAGE_ROLLUP`  | Escrow for region r      | Usage recognized    | Regional shard, every 5 s per active account    |
| `TRUE_UP`       | Customer credit or usage | The other side      | Hourly reconciliation against the rated ledger  |
| `GRANT_EXPIRY`  | Customer credit          | Breakage revenue    | Scheduled job at grant expiry                   |

Ledger writes scale with escrow moves and rollups, not requests: a few writes per active account per 30 s.

**Invariant (new, made explicit by the merge):** for every account and closed period, usage recognized in the balance ledger (`USAGE_ROLLUP` + `TRUE_UP`) equals the rated ledger total for that period. Reconciliation proves it before the statement or invoice is issued.

### Money representation

Every amount is a `long` count of US cents (`Cents` in `libs:money`): balances, holds, leases, escrow, ledger entries, top-ups, rated amounts, invoice lines and the fail-open allowance. Postgres `BIGINT`, Protobuf `int64`. Money code never uses `BigDecimal`, `BigInteger`, `double` or `float`, nor `int` (it overflows at about \$21 million). Allowance budgets count integer usage units instead. USD only for now; another currency would use its own smallest unit.

- **Prices** are `Cents.Rate(cents, perUnits)`: whole cents per a fixed number of units. \$15 per million tokens is `Rate(1500, 1_000_000)`; \$0.075 per million is `Rate(75, 1_000_000_000)`.
- **Discounts** are `int` basis points (2000 = 20%), applied once when a contract rate is created. A discount that gives a fractional rate is rejected; the SKU then needs a larger `perUnits`.
- **No per-request rounding.** One token at \$15/M costs 0.0015¢, so rounding each request and adding cents drifts (a request under half a cent would bill nothing). Every place that spends or bills money keeps a running quantity per SKU and charges `Cents.charge(before, after, rate) = forTotal(after) − forTotal(before)`. The charges sum exactly to `forTotal(final)` in any arrival order.
- **Holds** use `Cents.hold`, which adds 1 cent because concurrent requests move the running total.

| Where                                   | Representation                                                  | Rounding                                                            |
|-----------------------------------------|-----------------------------------------------------------------|---------------------------------------------------------------------|
| Hot path: leases, holds, shard counters | `long` cents; allowances in integer usage units                 | None; atomic counters (holds from `Cents.hold`)                     |
| Rated ledger                            | `long` cents per `(org, SKU, hour)`: `Cents.charge` increments  | Telescoping: increments sum to `forTotal` of the period quantity    |
| Balance ledger and `TRUE_UP`            | `long` cents; `TRUE_UP` is rated amount minus recognized usage  | None beyond the rated increments, so the balance converges exactly  |
| Invoice line                            | `Cents.forTotal` of the period total                            | Once, half-even; error under half a cent per account, SKU, period   |

Rounding each request instead would drift: in a simulation of one million requests it under-billed by 222¢. `forTotal` splits `units` into whole `perUnits` blocks and a remainder to avoid `long` overflow, and decides half-even ties by the parity of the whole result (not of the remainder part alone), so `Rate(1, 2)` gives 3 units → 2¢ and 7 units → 4¢.

## Integration seams

Seven contracts join the legs, and each is keyed by an ID that both sides can recompute, so a retry or replay on either side is recognized as a duplicate.

| \#  | Seam                  | From → to                                                              | Carries                                               | Idempotency key                                        | Guarantee                                              |
|-----|-----------------------|------------------------------------------------------------------------|-------------------------------------------------------|--------------------------------------------------------|--------------------------------------------------------|
| 1   | Shared rating library | One library → gateway holds and settlement, rating engine, weight book | SKU resolution, prices, contract overrides, weights   | Library version recorded on every settle and rated row | Same inputs give the same amount; drift pages the team |
| 2   | Usage event           | Inference host → Kafka `usage.raw`                                     | Quantities by meter, as facts                         | `requestId:meterCode`                                  | At-least-once delivery plus dedupe                     |
| 3   | Settle record         | Gateway → `budget.settle` topic                                        | Held and settled amounts per budget, fail-open flag   | `requestId` + sequence                                 | Joined to rated usage hourly by request ID             |
| 4   | True-up               | Reconciliation job → balance ledger                                    | Rated amount minus recognized usage, per account-hour | `(org, hour, rating_version)`                          | Posted once per rating version                         |
| 5   | Top-up                | Stripe webhook → balance ledger                                        | Confirmed payment                                     | Stripe payment ID                                      | Stripe retries; duplicates are no-ops                  |
| 6   | Invoice lines         | Rated ledger → Stripe                                                  | Period amounts per SKU                                | `hash(org, sku, period, ledger_version)`               | Pushed state kept in our table                         |
| 7   | Budget changes        | Ledger and config → shard → gateways                                   | Modes, caps, fail policies                            | Budget `version`                                       | Pushed on lease renewal and pub/sub within 5 s         |

### Decision: settle records travel separately from usage events

The enforcement design has the settle record "ride the existing usage event", but the metering design emits that event from the inference host, while settlement happens in the gateway. **Resolution:** the gateway publishes its settle record on its own topic keyed by request ID, and the hourly true-up joins the two by request ID. This keeps the gateway's record and the host's event as two independent references for completeness reconciliation, and still lets every hold be matched to its rated usage.

``` java
/** Published by the gateway when a request settles; joined to rated usage by requestId. */
public record SettleRecord(
        String requestId,                    // same ID as RequestUsage and the reservation
        String orgId,
        String homeRegion,
        Instant eventTime,                   // bucketed by the same event-time hour as metering
        Map<String, Long> heldByBudget,      // budgetId -> held, in the budget's unit
        Map<String, Long> settledByBudget,   // budgetId -> actual, cents or usage units
        boolean failOpen,                    // admitted under the fail-open allowance
        String ratingLibraryVersion,
        long sequence) {}                    // a retried settle reuses its sequence
```

``` java
/** Hourly, per account: post the gap between the invoice-grade amount and recognized usage. */
public Optional<LedgerEntry> trueUp(String orgId, Instant hour, int ratingVersion) {
    long rated = ratedLedger.amountFor(orgId, hour, ratingVersion);         // cents
    long recognized = balanceLedger.recognizedUsage(orgId, hour);          // rollups + earlier true-ups
    long gap = Math.subtractExact(rated, recognized);
    if (gap == 0) {
        return Optional.empty();
    }
    String key = orgId + ":" + hour + ":" + ratingVersion;                  // posted once per version
    return Optional.of(balanceLedger.postIdempotent(key, EntryType.TRUE_UP, gap));
}
```

A late re-rating produces a new `rating_version`, so its correction flows to the balance as one more true-up, not an edit.

## Correctness: idempotency, reconciliation and failure

Exactly-once billing is at-least-once delivery plus an idempotent step at every hop; reconciliation then proves nothing is missing. No stage trusts another to deduplicate for it.

### Idempotency, end to end

| Step                           | Key                                      | Mechanism                                                                          |
|--------------------------------|------------------------------------------|------------------------------------------------------------------------------------|
| Reserve, extend, settle        | `requestId` + sequence                   | Idempotent shard operations                                                        |
| Escrow grants, leases, rollups | Epoch (fencing token)                    | Ledger and gateways reject stale epochs after failover                             |
| Host → Kafka                   | Producer ID + sequence                   | Idempotent producer, `acks=all`                                                    |
| Stream dedupe                  | `requestId:meterCode`                    | Flink keyed state, 7-day TTL                                                       |
| Aggregate sink                 | Batch ID per subtask per checkpoint      | Stored in checkpointed state; written to `applied_batches` in the same transaction |
| Archive replay                 | None needed                              | Recomputes sealed hours and overwrites them                                        |
| Rated ledger                   | `(org, sku, hour, rating_version)`       | New version is a new key; differences become adjustments                           |
| True-up                        | `(org, hour, rating_version)`            | Posted once per version                                                            |
| Top-up                         | Stripe payment ID                        | Idempotent webhook                                                                 |
| Auto-recharge                  | Org ID + recharge sequence               | Stripe idempotency key                                                             |
| Invoice push                   | `hash(org, sku, period, ledger_version)` | Our own pushed-state table                                                         |

Keys come from the business identity of the work, never from processing time or an ID generated at send time.

### Reconciliation loops

| Loop         | Compares                                                                                                                   | Cadence                                         | On mismatch                                                                                     |
|--------------|----------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------|-------------------------------------------------------------------------------------------------|
| Completeness | Gateway logs, host control totals (`usage.control`), raw archive, `usage_hourly` + DLQ + pending corrections, rated ledger | Hourly on sealed hours; full daily              | Narrow by org, anti-join on `requestId`, re-emit from the gateway's record with the original ID |
| Credit       | Rated ledger vs balance ledger, including fail-open admissions and stranded credit                                         | Hourly                                          | Post `TRUE_UP`; a persistent gap (stale price cache, missing meter) pages the team              |
| Allowances   | Window counters vs metered usage in units                                                                                  | Hourly                                          | Correct only when drift exceeds about 1%                                                        |
| Collection   | Stripe invoiced totals vs rated ledger; Stripe payments vs `TOP_UP` entries (proposed)                                     | Before invoice finalization; daily for payments | Retry with the same keys; investigate drift inside Stripe                                       |

A request goes undetected only if the gateway log and the host's spool lose it together; they are separate systems, so one correlated failure is not enough.

### Failure modes across both legs

| Failure                               | What happens                                                                                      | Customer impact                                      |
|---------------------------------------|---------------------------------------------------------------------------------------------------|------------------------------------------------------|
| Kafka unavailable                     | Hosts spool to disk; spool depth pages on-call                                                    | None                                                 |
| Inference host lost with a full spool | Gateway-log reconciliation re-emits usage with the original request ID                            | None once recovered                                  |
| Inference host dies mid-stream        | Holds auto-settle at the held amount after max duration plus grace; true-up corrects              | Charged at most the held chunk until true-up         |
| Gateway host crashes                  | Its leases are stranded and treated as spent until metering reports its usage, then credited back | Near-\$0 accounts may be denied slightly early       |
| Shard leader fails                    | Raft elects a new leader with a new epoch; `LEASE` accounts keep spending                         | `DIRECT` accounts briefly use the fail policy        |
| Whole shard group down                | Gateways apply each budget's fail policy, split across the org's cell hosts                       | Trusted accounts continue up to `F_a`; others denied |
| Region partitioned from home          | The region spends only the escrow it holds                                                        | Only near-\$0 accounts in that region                |
| Home ledger unavailable               | Regions keep spending escrow; grants and top-ups queue (Stripe retries webhooks)                  | Top-ups delayed                                      |
| Flink job down for hours              | Resumes from checkpoint; idempotent sinks absorb replayed batches; admission unaffected           | Dashboards and true-ups lag                          |
| Processing bug found later            | Replay sealed hours, re-rate, post adjustments; the true-up carries them to balances              | Corrected on the next statement or invoice           |
| Model launched without SKUs           | Launch gate blocks production traffic                                                             | None                                                 |
| Stripe API errors                     | Retry with the same keys; reconcile invoiced totals                                               | Invoice or top-up delayed                            |

**Cross-leg rule:** enforcement never reads the metering pipeline at admission, so a metering outage cannot deny or over-admit requests. It only delays the return of stranded credit and the hourly true-ups, which can make near-\$0 accounts deny early, never overspend.

## SLOs and monitoring

The signals that matter most are financial (money let through, revenue left unbilled, drift between ledgers), not only system health. Targets are proposed starting points.

| Signal                            | Target or alert                                      | Leg                      | Why                                           |
|-----------------------------------|------------------------------------------------------|--------------------------|-----------------------------------------------|
| Added admission latency, `LEASE`  | p99 under 1 ms                                       | Enforcement              | Hot path stays in-process                     |
| Added admission latency, `DIRECT` | p99 under 5 ms in-region                             | Enforcement              | Near-\$0 accounts only                        |
| Overdraft per account             | Under `O_max(a)`; alert at 50% of it                 | Enforcement              | Proves the bound holds                        |
| False denials                     | Under 0.01% of requests denied while credit remained | Enforcement              | Catches stranded credit and bad lease sizing  |
| Top-up to resume                  | p99 under 5 s                                        | Enforcement + collection | Customers at \$0 are blocked                  |
| Ingest freshness                  | p99 under 60 s, event time to `usage_hourly`         | Metering                 | Dashboards and spend alerts                   |
| Edge spool depth                  | Page if non-empty for over 10 min on any host        | Metering                 | Leading indicator of loss                     |
| DLQ age                           | Page if any event is older than 1 h                  | Metering                 | Each event is unbilled revenue                |
| Unknown-SKU resolutions           | Page on any                                          | Metering                 | Launch gate failed                            |
| Real-time vs rated drift          | Under 0.1% per account-hour; zero after true-up      | Seam                     | Same rating library on both paths             |
| Completeness drift                | Zero after period close                              | Seam                     | Proves nothing was lost                       |
| Tier true-up at close             | Zero for every customer                              | Rating                   | Non-zero means a lost or duplicated increment |
| Stripe push                       | 100% of lines acknowledged before finalization       | Invoicing                | Revenue actually invoiced                     |
| Period close                      | Invoices within 24 h of grace window end             | Invoicing                | Finance close timeline                        |
| Fail-open spend                   | Reported daily, owned by Finance                     | Enforcement              | Revenue at risk                               |
| Stranded credit                   | Total and age; alert when older than 1 h             | Seam                     | Unreconciled host or shard crashes            |
| Mode churn                        | Switches per account per hour                        | Enforcement              | Hysteresis is working                         |

**Load shape:** Kafka ingest is about 100 MB/s at peak; `usage_hourly` takes one row per distinct `(org, sku, hour)` per checkpoint, not per event; the balance ledger takes a few writes per active account per 30 s; the hot path takes about 100K admissions/s, almost all in-process.

## Implementation decisions

Choices made while building this repository that refine the design above. The `Q` labels are the original question numbers, which code comments and migrations still cite.

### Platform and toolchain

- Versions live only in `gradle/libs.versions.toml`. Kafka clients stay on 4.3.x, Flink on 2.2.x (2.3 waits for a matching Kafka connector) and Ratis on 3.2.x until deliberately upgraded. The Gradle daemon is pinned to JDK 21 (`gradle/gradle-daemon-jvm.properties`).
- Flink-bound modules (`libs:money`, `libs:domain`, `libs:contracts`, `libs:rating`, `jobs:*`) compile for Java 17; everything else for Java 21. Error Prone 2.43+ runs only on JDK 21, so the Java 17 modules use the `errorproneJdk17` line (2.42.x); revisit when they move to 21.

### Local environment

- `infra/docker-compose.yml` pins every image tag. SeaweedFS is the S3-compatible store (MinIO no longer publishes community images); the one-shot `create-buckets` service creates `archive` and `flink` and is idempotent. SeaweedFS needs `-ip.bind=0.0.0.0` to be reachable from other containers.
- The Kafka Connect image is built locally (`infra/connect`), resolving the Iceberg sink plus a minimal Hadoop subset (with Woodstox and the commons libraries Hadoop `Configuration` loads). `ICEBERG_VERSION` there must match `iceberg` in the version catalog.
- Postgres has one schema per service (`infra/postgres/init`); a service never reads another's schema. Postgres 18 keeps data under `/var/lib/postgresql`. Compose credentials are local-only defaults.
- Windows: scripts set `MSYS_NO_PATHCONV=1` for `docker exec`, strip the `\r` in its output, pass Windows paths (`pwd -W`) under Git Bash, and Make recipes call `bash ./gradlew` because Windows make runs through cmd.

### Catalog immutability

The catalog (`services:catalog`, schema `catalog`) is never edited:

- Triggers reject every `DELETE` and `TRUNCATE`, and every `UPDATE` except setting `effective_until` on a row where it is `NULL`, with every other column unchanged. That end date must not be before the current hour, so hours already rated keep their price and re-rating is reproducible. Tier rows never change.
- Exclusion constraints (btree_gist) reject overlapping half-open periods per business key: SKU dimensions, band rule per model, list price per SKU, contract price per org and SKU, weight per SKU. Price periods start and end on an hour.
- Money is `bigint rate_cents / per_units`; weights are `bigint units / per_tokens`. A test bans `numeric`, `real`, `double` and `money` column types. Primary keys are business keys, so writes use `ON CONFLICT DO NOTHING`. Tier shape (ascending `up_to`, an open last tier, one shared `per_units`) is checked by `Pricing` when the catalog loads.
- A mistaken row is closed from the current hour on and followed by a corrected row.

### Host control totals

- One `billing.v1.ControlTotals` per (host, event hour) with usage since the last publish, carrying the **delta** of requests and per-meter quantities. Counting happens when usage reaches `HostUsageSink`, before Kafka, so totals state what the host produced regardless of delivery.
- `sequence` increases by one per message per host and is persisted (atomic file replace in the control spool) before hand-off, so it survives restarts; a spool resend carries the same bytes and sequence. Key = host ID; separate producer and spool (`data/control-spool`), so a usage outage cannot also hide the totals.
- Reconciliation sums deltas per (host, hour) and deduplicates on (host, sequence); a missing sequence means a lost control message. A crash between persisting a sequence and spooling its message leaves a gap that correctly flags possible loss.

### Raw usage archive

- `usage.raw` is protobuf with no schema registry. `libs:usage-connect` `UsageRawConverter` decodes it into a Connect struct; it is shaded (protobuf relocated) and staged by `make connect-plugin`. `usage.raw` stays the only copy: no JSON mirror topic.
- Table `billing.usage_raw` (Iceberg REST catalog on SeaweedFS): `request_id`, `org_id`, `workspace_id`, `api_key_id`, `model`, `service_tier`, `host_id`, `event_time`, `quantities` (map of permanent meter code to long), partitioned by `hour(event_time)`; schema evolution off.
- Connector `usage-raw-iceberg` (`infra/connect/connectors`, registered by `make connectors`) uses its own control topic `usage.archive.control` and commits every 10 s. The archive keeps every delivered copy; replay dedupes on `requestId:meterCode`, like the stream. The health check requires the connector and its tasks to be RUNNING.

### Q2 — Context band

Per model, prompt = input + cache-write + cache-read tokens. A prompt **above** the threshold (`>`) selects the above band; at the threshold it stays in the base band. The band applies to every meter of the request; a model with no rule uses band `base`. Implemented in `ContextBandRule`.

### Q3 — Volume tiers

Tiers are graduated only (no all-units). All tiers of one price share one `perUnits`, so `F(total)` sums the tier portions exactly and rounds half-even once. The golden tier table is \$15/M up to 1B tokens and \$12/M above (30M arriving at 990M = 39,000¢).

### Q5 — Lease reporting

Gateways report leases with `ReportLease(host_id, budget_id, lease_id, spent, unused)`: `spent` moves from leased to spent, `unused` is released, idempotent per lease. `LeaseGrant` carries a `lease_id` per (host, budget), because one epoch covers many grants. An unreported lease is charged in full at its 2 s TTL; a late report posts the difference. Leases and reports are forgotten after the max hold time (11 min), after which reconciliation corrects. Gateways report their leases on shutdown.

### Q7 — Catalog snapshot API

Services outside the catalog read it over HTTP, never its schema: `GET /v1/catalog/snapshot?hour=` (protobuf `billing.v1.CatalogSnapshot`, the rows in effect that hour plus the catalog version) and `GET /v1/catalog/version` on `services:catalog`. `HttpCatalogClient` in `libs:contracts` builds the same `RatingLibrary`; the gateway, the aggregator, replay and the usage view use it.

### Q8 — Committed use

A commitment is `commit_cents` per org and billing period (calendar month, UTC), append-only in `rating.commitment` with a contract version. While it applies, all of the org's SKUs are charged at a flat rate (the price's rate, or its **first tier's** for graduated pricing; no tier progression), still as `Cents.charge` on per-SKU running totals. Each rated row draws down the remaining commitment in (hour, SKU) order; `drawdown_cents` is the covered part and the rest is overage at the same rate. At close, the unused commitment is billed as a shortfall line (`CommitmentShortfall`, pseudo-SKU `commit-shortfall`).

### Q9 — Credit grants

A grant (promotional or purchased) is funded by a `TOP_UP` keyed `grant:<grant_id>` and recorded in `balance_ledger.credit_grant`; there is no separate promotional account (that would be a new entry type). Which lot a debit draws is derived by replaying customer-credit postings in time order: debits draw the live lot that expires first, cash last. Returned credit undoes the most recent draws first; credit whose lot already expired goes to cash.

### Q10 — Top-up API

`POST /v1/top-ups` on `services:balance-ledger` calls `BalanceLedger.post(TOP_UP)` with the caller's key: 201 posted, 200 duplicate, 409 key reused with other content. `services:payments` turns `payment_intent.succeeded` into a top-up keyed `stripe:<payment_intent_id>` and posts it through `HttpTopUps` (`payments.ledger-url`); it then publishes a `BudgetChange` (cause `top_up`) so gateways drop a cached deny.

### Q12 — Usage by workspace and key

`usage_hourly` stays keyed by `(org, sku, hour)` for rating. A second rollup, `metering.usage_hourly_by_key` (adding workspace and API key), is written by the same sink in the same transaction, replaced with it on replay, and serves only the usage view (`GET /v1/usage`).

## Billing Integration Platform (reserved)

This section is a placeholder for the [Self-Serve Billing Integration Platform design](https://claude.ai/code/artifact/5d310048-9b3a-4701-8a5b-a08318e955b7), to be merged later. When it lands, check it against the seams it is most likely to touch: top-up webhooks (seam 5), invoice lines to Stripe (seam 6), the collection paths in Stage 3, and the SKU and price catalog shared by the rating library.

## Open questions

**Raised by the merge**

- Settle-record topic: retention and join window, given 1 h allowed lateness in metering and hourly true-ups.

- Prepaid period statement: an invoice with zero due, or a statement only? Who receives it?

- Collection status into enforcement: which past-due trigger and grace period move a postpaid org to fail-closed or a spend limit?

- Payment reconciliation: confirm a daily Stripe-payments vs `TOP_UP` check is wanted, and who owns breaks.

- Multi-region alignment: metering's regional ingest and global dedupe must agree with each account's home region for the balance ledger.

- Store choice: can the rated ledger and the balance ledger share one chart of accounts and store, or stay separate?

**Carried from metering**

- Is a 24 h grace window enough for Finance's close?

- Back-billing policy after an undercount bug: always bill, bill above a threshold, or credit only?

- Partial usage when an inference process crashes mid-stream: can token counts be recovered?

**Carried from enforcement**

- Stop reason and API shape when a stream is cut at a checkpoint.

- Fail-open allowance `F_a`: fixed dollars, minutes of burn, or tiered by account age (Finance and Legal)?

- A strict per-budget mode (reserve `max_tokens`, always `DIRECT`) for customers who want no overdraft?

- Batch API: reserve at submission or at dispatch (dispatch assumed)?

- Chunk size: fixed 2,048 tokens, or scaled with price and runway?

- Data residency for escrow and holds of EU accounts.

- Shard technology: custom Raft service or an existing CP store?

- Usage windows: anchored or rolling per plan; very large orgs' per-user windows on one shard; who sets allowance weights.

## Sources

- [Usage Metering & Billing Pipeline — Design Doc](https://claude.ai/code/artifact/8f547ae1-c31f-48ff-8e97-0648ec5540e8): basis for Stage 1, rating, invoicing, idempotency and completeness reconciliation.

- [Prepaid Balance & Spend-Limit Enforcement — Design Doc](https://claude.ai/code/artifact/176f0567-e43b-495b-9b2f-da00c97741da): basis for Stage 2, the balance ledger, top-ups and the credit true-up.

- Items marked new or proposed, the seam table, the settle-record decision, the ledger invariant and the money-representation table are additions made by this merge.
