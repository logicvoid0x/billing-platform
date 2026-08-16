package com.logicvoid0x.billing.balanceledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.balanceledger.BalanceLedger.Entry;
import com.logicvoid0x.billing.money.Cents;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class EscrowGranterTest {
  @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.6");

  private static final Instant T = Instant.parse("2026-11-01T10:00:00Z");

  /** $1.00 per second of regional burn. */
  private static final Cents.Rate DOLLAR_PER_SECOND = new Cents.Rate(100, 1);

  private static BalanceLedger ledger;
  private static EscrowGranter granter;

  @BeforeAll
  static void setUp() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(PG.getJdbcUrl());
    ds.setUser(PG.getUsername());
    ds.setPassword(PG.getPassword());
    BalanceLedgerMigrations.migrate(ds);
    ledger = new BalanceLedger(ds);
    granter = new EscrowGranter(ledger, 500, 10); // keep $5 home; grants of at least 10 cents
  }

  @Test
  void grantsCoverThirtySecondsOfBurnRoundedUp() {
    assertThat(granter.grantSize(DOLLAR_PER_SECOND)).isEqualTo(3_000);
    assertThat(granter.grantSize(new Cents.Rate(1, 7))).as("30/7 cents, rounded up").isEqualTo(10);
    assertThat(granter.grantSize(new Cents.Rate(7, 3))).isEqualTo(70);
  }

  @Test
  void grantsDebitSpendableCreditAndStopNearZero() throws Exception {
    String org = "org_esc";
    ledger.post(new Entry("pay_esc", EntryType.TOP_UP, org, null, 7_000, T));

    assertThat(granter.request(org, "us-east", DOLLAR_PER_SECOND, 1, T).cents()).isEqualTo(3_000);
    assertThat(granter.request(org, "us-east", DOLLAR_PER_SECOND, 1, T).cents())
        .as("a retry of request 1 gets the same grant, not a second one")
        .isEqualTo(3_000);
    assertThat(ledger.spendable(org)).isEqualTo(4_000);

    // 4,000 - 3,000 = 1,000 >= 500 reserve: granted. Then 1,000 - 3,000 < 500: refused.
    assertThat(granter.request(org, "eu-west", DOLLAR_PER_SECOND, 1, T).cents()).isEqualTo(3_000);
    EscrowGranter.Grant refused = granter.request(org, "eu-west", DOLLAR_PER_SECOND, 2, T);
    assertThat(refused).isEqualTo(new EscrowGranter.Grant(0, true));
    assertThat(ledger.spendable(org)).isEqualTo(1_000).isGreaterThanOrEqualTo(500);
  }

  @Test
  void concurrentGrantsNeverDipBelowTheReserve() throws Exception {
    String org = "org_race";
    ledger.post(new Entry("pay_race", EntryType.TOP_UP, org, null, 10_000, T));
    var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
    try {
      var results = new java.util.ArrayList<java.util.concurrent.Future<EscrowGranter.Grant>>();
      for (int i = 0; i < 20; i++) {
        long seq = i;
        results.add(
            pool.submit(
                () -> granter.request(org, "region-" + (seq % 4), DOLLAR_PER_SECOND, seq, T)));
      }
      long granted = 0;
      for (var f : results) {
        granted += f.get().cents();
      }
      // 10,000 with a 500 reserve fits three grants of 3,000.
      assertThat(granted).isEqualTo(9_000);
      assertThat(ledger.spendable(org)).isEqualTo(1_000);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void idleEscrowReturnsAfterFiveMinutesCappedAtWhatIsHeld() throws Exception {
    String org = "org_idle";
    ledger.post(new Entry("pay_idle", EntryType.TOP_UP, org, null, 10_000, T));
    granter.request(org, "ap-south", DOLLAR_PER_SECOND, 1, T);
    ledger.post(new Entry("rollup_idle", EntryType.USAGE_ROLLUP, org, "ap-south", 1_000, T));
    Instant lastUse = T.plusSeconds(10);

    assertThat(granter.returnUnused(org, "ap-south", 2_000, 1, lastUse, lastUse.plusSeconds(299)))
        .as("not idle yet")
        .isZero();
    // The region reports more than the ledger still holds; only the held 2,000 comes back.
    assertThat(granter.returnUnused(org, "ap-south", 5_000, 1, lastUse, lastUse.plusSeconds(300)))
        .isEqualTo(2_000);
    assertThat(granter.returnUnused(org, "ap-south", 5_000, 1, lastUse, lastUse.plusSeconds(301)))
        .as("retry")
        .isEqualTo(2_000);
    assertThat(ledger.balance(AccountKind.ESCROW.accountId(org, "ap-south"))).isZero();
    assertThat(ledger.spendable(org)).isEqualTo(10_000 - 1_000);
  }
}
