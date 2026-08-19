package com.logicvoid0x.billing.balanceledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.balanceledger.BalanceLedger.Entry;
import com.logicvoid0x.billing.money.Cents;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Phase 9 "Done when": random top-ups, grants, escrow requests, rollups, true-ups, returns and
 * expiries never take spendable credit below the overdraft bound, and every entry balances.
 *
 * <p>Overdraft can come only from cost incurred before it was held: here, rollups that exceed the
 * escrow held by at most {@link #DELTA} (the design's one-chunk overshoot) and positive true-up
 * gaps. Escrow grants alone never take spendable credit below the reserve.
 */
class LedgerProperties {
  static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.6");

  private static final long RESERVE = 200;
  private static final long DELTA = 40;
  private static final String[] REGIONS = {"us", "eu", "ap"};
  private static final AtomicInteger ORGS = new AtomicInteger();

  private static PGSimpleDataSource ds;
  private static BalanceLedger ledger;
  private static EscrowGranter granter;
  private static CreditGrants grants;

  @BeforeContainer
  static void start() {
    PG.start();
    ds = new PGSimpleDataSource();
    ds.setUrl(PG.getJdbcUrl());
    ds.setUser(PG.getUsername());
    ds.setPassword(PG.getPassword());
    BalanceLedgerMigrations.migrate(ds);
    ledger = new BalanceLedger(ds);
    granter = new EscrowGranter(ledger, RESERVE, 10);
    grants = new CreditGrants(ds, ledger);
  }

  /** One random operation; fields are interpreted per kind. */
  record Op(int kind, long amount, int region, int days) {}

  @Provide
  Arbitrary<List<Op>> ops() {
    Arbitrary<Op> op =
        Combinators.combine(
                Arbitraries.integers().between(0, 6),
                Arbitraries.longs().between(1, 3_000),
                Arbitraries.integers().between(0, REGIONS.length - 1),
                Arbitraries.integers().between(0, 3))
            .as(Op::new);
    return op.list().ofMinSize(1).ofMaxSize(25);
  }

  @Property(tries = 40)
  void spendableStaysAboveTheOverdraftBoundAndEveryEntryBalances(@ForAll("ops") List<Op> ops)
      throws Exception {
    String org = "org_prop_" + ORGS.incrementAndGet();
    Instant now = Instant.parse("2026-11-01T00:00:00Z");
    Map<String, Long> lastUse = new HashMap<>();
    Map<String, Long> overshoot = new HashMap<>();
    long positiveTrueUps = 0;
    int seq = 0;
    for (Op op : ops) {
      seq++;
      String region = REGIONS[op.region()];
      String escrow = AccountKind.ESCROW.accountId(org, region);
      switch (op.kind()) {
        case 0 ->
            ledger.post(
                new Entry(org + ":pay:" + seq, EntryType.TOP_UP, org, null, op.amount(), now));
        case 1 ->
            grants.grant(
                org + ":g" + seq,
                org,
                op.amount(),
                now,
                now.plus(Duration.ofDays(1 + op.days())),
                "PROMOTIONAL");
        case 2 -> {
          granter.request(org, region, new Cents.Rate(op.amount(), 30), seq, now);
          lastUse.put(region, now.getEpochSecond());
        }
        case 3 -> {
          // Usage recognized: at most what is held plus a one-chunk overshoot.
          long held = ledger.balance(escrow);
          long used = Math.min(op.amount(), Math.max(held, 0) + DELTA);
          if (used > 0) {
            ledger.post(
                new Entry(org + ":roll:" + seq, EntryType.USAGE_ROLLUP, org, region, used, now));
            overshoot.merge(region, Math.max(0, used - Math.max(held, 0)), Long::sum);
            lastUse.put(region, now.getEpochSecond());
          }
        }
        case 4 -> {
          long gap = op.amount() % 301 - 150; // rated minus recognized, either sign
          if (gap != 0) {
            ledger.post(new Entry(org + ":tu:" + seq, EntryType.TRUE_UP, org, null, gap, now));
            positiveTrueUps += Math.max(gap, 0);
          }
        }
        case 5 -> {
          Instant used = Instant.ofEpochSecond(lastUse.getOrDefault(region, 0L));
          granter.returnUnused(org, region, ledger.balance(escrow), seq, used, now);
        }
        default -> {
          now = now.plus(Duration.ofHours(6L * (1 + op.days())));
          grants.expireDue(org, now);
        }
      }

      long spendable = ledger.spendable(org);
      assertThat(spendable)
          .as("spendable above the overdraft bound after %s", op)
          .isGreaterThanOrEqualTo(Math.min(0, RESERVE) - positiveTrueUps);
      for (String r : REGIONS) {
        assertThat(ledger.balance(AccountKind.ESCROW.accountId(org, r)))
            .as("escrow of %s overdrawn at most by its overshoot", r)
            .isGreaterThanOrEqualTo(-overshoot.getOrDefault(r, 0L));
      }
      List<CreditGrants.Lot> lots = grants.lots(org, now);
      assertThat(lots.stream().mapToLong(CreditGrants.Lot::remaining).sum())
          .as("grant lots sum to spendable credit")
          .isEqualTo(spendable);
      assertThat(lots)
          .filteredOn(l -> !l.id().equals(CreditGrants.CASH))
          .allSatisfy(l -> assertThat(l.remaining()).isGreaterThanOrEqualTo(0));
    }
    assertThat(unbalancedEntries()).isZero();
  }

  private static long unbalancedEntries() throws Exception {
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT count(*) FROM (SELECT entry_key FROM balance_ledger.posting"
                    + " GROUP BY entry_key HAVING sum(debit_cents) <> sum(credit_cents)) t")) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
