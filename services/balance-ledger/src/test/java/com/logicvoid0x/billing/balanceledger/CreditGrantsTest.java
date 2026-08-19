package com.logicvoid0x.billing.balanceledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.balanceledger.BalanceLedger.Entry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class CreditGrantsTest {
  @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.6");

  private static final Instant DAY0 = Instant.parse("2026-11-01T00:00:00Z");
  private static BalanceLedger ledger;
  private static CreditGrants grants;

  private static Instant day(int d) {
    return DAY0.plus(Duration.ofDays(d));
  }

  @BeforeAll
  static void setUp() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(PG.getJdbcUrl());
    ds.setUser(PG.getUsername());
    ds.setPassword(PG.getPassword());
    BalanceLedgerMigrations.migrate(ds);
    ledger = new BalanceLedger(ds);
    grants = new CreditGrants(ds, ledger);
  }

  private static List<String> lots(String org, Instant at) throws Exception {
    return grants.lots(org, at).stream().map(l -> l.id() + "=" + l.remaining()).toList();
  }

  @Test
  void debitsDrawTheGrantThatExpiresFirstThenCash() throws Exception {
    String org = "org_g1";
    grants.grant("g1_late", org, 1_000, DAY0, day(10), "PROMOTIONAL");
    grants.grant("g1_soon", org, 1_000, DAY0, day(5), "PURCHASED");
    ledger.post(new Entry("pay_g1", EntryType.TOP_UP, org, null, 1_000, DAY0));
    ledger.post(new Entry("esc_g1", EntryType.ESCROW_GRANT, org, "us", 1_500, day(2)));

    assertThat(lots(org, day(3))).containsExactly("g1_soon=0", "g1_late=500", "cash=1000");
    assertThat(ledger.spendable(org)).isEqualTo(1_500);

    assertThat(grants.expireDue(org, day(6))).as("the soon grant is already used up").isZero();
    assertThat(grants.expireDue(org, day(11))).isEqualTo(500);
    assertThat(grants.expireDue(org, day(12))).as("expiry is posted once").isZero();
    assertThat(ledger.spendable(org)).isEqualTo(1_000);
    assertThat(ledger.balance(AccountKind.BREAKAGE_REVENUE.accountId(org, null))).isEqualTo(500);
    assertThat(lots(org, day(12))).containsExactly("g1_soon=0", "g1_late=0", "cash=1000");
  }

  @Test
  void anExpiredGrantIsNotDrawnAndItsWholeRemainderExpires() throws Exception {
    String org = "org_g2";
    grants.grant("g2", org, 800, DAY0, day(1), "PROMOTIONAL");
    ledger.post(new Entry("pay_g2", EntryType.TOP_UP, org, null, 1_000, DAY0));
    ledger.post(new Entry("esc_g2", EntryType.ESCROW_GRANT, org, "us", 300, day(2)));
    assertThat(lots(org, day(3))).containsExactly("g2=800", "cash=700");
    assertThat(grants.expireDue(org, day(3))).isEqualTo(800);
    assertThat(ledger.spendable(org)).isEqualTo(700);
  }

  @Test
  void returnedEscrowGoesBackWhereItWasDrawnFrom() throws Exception {
    String org = "org_g3";
    grants.grant("g3", org, 1_000, DAY0, day(10), "PROMOTIONAL");
    ledger.post(new Entry("pay_g3", EntryType.TOP_UP, org, null, 1_000, DAY0));
    ledger.post(new Entry("esc_g3_1", EntryType.ESCROW_GRANT, org, "us", 1_400, day(1)));
    // Drawn: 1,000 from the grant, then 400 from cash. A 600 return undoes cash first.
    ledger.post(new Entry("ret_g3", EntryType.ESCROW_RETURN, org, "us", 600, day(2)));
    assertThat(lots(org, day(3))).containsExactly("g3=200", "cash=1000");

    // A return after the grant expired refunds to cash (open-questions Q9).
    String org2 = "org_g4";
    grants.grant("g4", org2, 500, DAY0, day(1), "PROMOTIONAL");
    ledger.post(new Entry("esc_g4", EntryType.ESCROW_GRANT, org2, "us", 500, DAY0.plusSeconds(60)));
    ledger.post(new Entry("ret_g4", EntryType.ESCROW_RETURN, org2, "us", 500, day(2)));
    assertThat(lots(org2, day(3))).containsExactly("g4=0", "cash=500");
    assertThat(grants.expireDue(org2, day(3))).isZero();
  }

  @Test
  void grantingIsIdempotentOnTheGrantId() throws Exception {
    String org = "org_g5";
    grants.grant("g5", org, 250, DAY0, day(3), "PURCHASED");
    grants.grant("g5", org, 250, DAY0, day(3), "PURCHASED");
    assertThat(ledger.spendable(org)).isEqualTo(250);
    assertThat(lots(org, day(1))).containsExactly("g5=250", "cash=0");
  }
}
