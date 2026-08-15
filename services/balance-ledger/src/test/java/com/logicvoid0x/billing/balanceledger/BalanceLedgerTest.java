package com.logicvoid0x.billing.balanceledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicvoid0x.billing.balanceledger.BalanceLedger.Entry;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class BalanceLedgerTest {
  @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.6");

  private static final Instant T = Instant.parse("2026-11-01T10:00:00Z");
  private static PGSimpleDataSource ds;
  private static BalanceLedger ledger;

  @BeforeAll
  static void migrate() {
    ds = new PGSimpleDataSource();
    ds.setUrl(PG.getJdbcUrl());
    ds.setUser(PG.getUsername());
    ds.setPassword(PG.getPassword());
    BalanceLedgerMigrations.migrate(ds);
    ledger = new BalanceLedger(ds);
  }

  private static void exec(String sql) throws SQLException {
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement()) {
      s.execute(sql);
    }
  }

  @Test
  void theSixEntryTypesMoveCreditBetweenTheRightAccounts() throws Exception {
    String org = "org_six";
    ledger.post(new Entry("pay_1", EntryType.TOP_UP, org, null, 10_000, T));
    ledger.post(new Entry("grant_1", EntryType.ESCROW_GRANT, org, "us-east", 3_000, T));
    ledger.post(new Entry("rollup_1", EntryType.USAGE_ROLLUP, org, "us-east", 1_200, T));
    ledger.post(new Entry("return_1", EntryType.ESCROW_RETURN, org, "us-east", 800, T));
    ledger.post(
        new Entry("trueup_1", EntryType.TRUE_UP, org, null, 50, T)); // rated above recognized
    ledger.post(new Entry("trueup_2", EntryType.TRUE_UP, org, null, -20, T)); // and back
    ledger.post(new Entry("expiry_1", EntryType.GRANT_EXPIRY, org, null, 100, T));

    assertThat(ledger.spendable(org)).isEqualTo(10_000 - 3_000 + 800 - 50 + 20 - 100);
    assertThat(ledger.balance(AccountKind.ESCROW.accountId(org, "us-east")))
        .isEqualTo(3_000 - 1_200 - 800);
    assertThat(ledger.balance(AccountKind.USAGE_RECOGNIZED.accountId(org, null)))
        .isEqualTo(1_200 + 50 - 20);
    assertThat(ledger.balance(AccountKind.BREAKAGE_REVENUE.accountId(org, null))).isEqualTo(100);
    assertThat(ledger.balance(AccountKind.CASH_CLEARING.accountId(org, null))).isEqualTo(-10_000);
  }

  @Test
  void writesAreIdempotentOnTheBusinessKeyAndConflictsAreRejected() throws Exception {
    Entry topUp = new Entry("pay_idem", EntryType.TOP_UP, "org_idem", null, 5_000, T);
    assertThat(ledger.post(topUp)).isTrue();
    assertThat(ledger.post(topUp)).as("webhook retried").isFalse();
    assertThat(ledger.spendable("org_idem")).isEqualTo(5_000);
    assertThatThrownBy(
            () -> ledger.post(new Entry("pay_idem", EntryType.TOP_UP, "org_idem", null, 6_000, T)))
        .isInstanceOf(BalanceLedger.ConflictingEntryException.class);
  }

  @Test
  void theDatabaseRejectsUnbalancedOrMistypedEntriesAndEdits() throws Exception {
    // Unbalanced: one debit line only.
    assertThatThrownBy(() ->
            exec(
                "BEGIN; INSERT INTO balance_ledger.entry VALUES ('bad_1', 'TOP_UP', 'org_db', now());"
                    + " INSERT INTO balance_ledger.posting VALUES ('bad_1', 1, 'org_db:CASH_CLEARING',"
                    + " 'CASH_CLEARING', 100, 0); COMMIT;"))
        .hasMessageContaining("does not balance");
    // Balanced, but a TOP_UP may not credit escrow.
    assertThatThrownBy(() ->
            exec(
                "BEGIN; INSERT INTO balance_ledger.entry VALUES ('bad_2', 'TOP_UP', 'org_db', now());"
                    + " INSERT INTO balance_ledger.posting VALUES"
                    + " ('bad_2', 1, 'org_db:CASH_CLEARING', 'CASH_CLEARING', 100, 0),"
                    + " ('bad_2', 2, 'org_db:ESCROW:eu', 'ESCROW', 0, 100); COMMIT;"))
        .hasMessageContaining("does not allow");
    // An entry with no postings.
    assertThatThrownBy(() ->
            exec("INSERT INTO balance_ledger.entry VALUES ('bad_3', 'TOP_UP', 'org_db', now())"))
        .hasMessageContaining("no postings");
    // Append-only.
    ledger.post(new Entry("pay_db", EntryType.TOP_UP, "org_db", null, 1, T));
    assertThatThrownBy(() ->
            exec("UPDATE balance_ledger.posting SET credit_cents = 2 WHERE entry_key = 'pay_db'"))
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> exec("DELETE FROM balance_ledger.entry WHERE entry_key = 'pay_db'"))
        .hasMessageContaining("append-only");
  }

  @Test
  void amountsMustBePositiveExceptATrueUpGap() {
    assertThatThrownBy(() -> new Entry("k", EntryType.TOP_UP, "org", null, -1, T))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Entry("k", EntryType.TRUE_UP, "org", null, 0, T))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> BalanceLedger.postings(new Entry("k", EntryType.ESCROW_GRANT, "org", null, 1, T)))
        .hasMessageContaining("needs a region");
  }
}
