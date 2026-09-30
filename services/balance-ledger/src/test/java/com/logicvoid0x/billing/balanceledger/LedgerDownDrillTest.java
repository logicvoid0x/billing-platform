package com.logicvoid0x.billing.balanceledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicvoid0x.billing.balanceledger.BalanceLedger.Entry;
import com.logicvoid0x.billing.money.Cents;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Drill "ledger down" (docs/drills.md): while the home balance ledger is unreachable, escrow
 * requests fail cleanly and nothing is posted; once it is back, the region's retry under the same
 * request sequence posts the grant exactly once. (Admission is unaffected by design: gateways never
 * call the ledger; they spend leases funded from escrow the region already holds.)
 */
@Testcontainers
class LedgerDownDrillTest {
  @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.6");

  private static final Instant T = Instant.parse("2026-11-01T10:00:00Z");

  /** The ledger database, with a switch that makes every connection attempt fail. */
  static final class Switchable implements DataSource {
    final DataSource inner;
    final AtomicBoolean down = new AtomicBoolean();

    Switchable(DataSource inner) {
      this.inner = inner;
    }

    @Override
    public Connection getConnection() throws SQLException {
      if (down.get()) {
        throw new SQLException("ledger database unreachable", "08001");
      }
      return inner.getConnection();
    }

    @Override
    public Connection getConnection(String u, String p) throws SQLException {
      return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
      return inner.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
      inner.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
      inner.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
      return inner.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      return inner.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      return inner.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
      return inner.isWrapperFor(iface);
    }
  }

  @Test
  void escrowGrantsWaitWhileTheLedgerIsDownAndResumeExactlyOnce() throws Exception {
    PGSimpleDataSource pg = new PGSimpleDataSource();
    pg.setUrl(PG.getJdbcUrl());
    pg.setUser(PG.getUsername());
    pg.setPassword(PG.getPassword());
    BalanceLedgerMigrations.migrate(pg);
    Switchable db = new Switchable(pg);
    BalanceLedger ledger = new BalanceLedger(db);
    EscrowGranter granter = new EscrowGranter(ledger, 100, 10);
    ledger.post(new Entry("pay_drill", EntryType.TOP_UP, "org_drill", null, 10_000, T));

    db.down.set(true);
    assertThatThrownBy(() -> granter.request("org_drill", "us", new Cents.Rate(100, 1), 7, T))
        .isInstanceOf(SQLException.class);

    db.down.set(false);
    assertThat(ledger.spendable("org_drill")).as("nothing posted while down").isEqualTo(10_000);
    assertThat(granter.request("org_drill", "us", new Cents.Rate(100, 1), 7, T).cents())
        .isEqualTo(3_000);
    assertThat(granter.request("org_drill", "us", new Cents.Rate(100, 1), 7, T).cents())
        .as("the region retries the same request")
        .isEqualTo(3_000);
    assertThat(ledger.spendable("org_drill")).isEqualTo(7_000);
  }
}
