package com.logicvoid0x.billing.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class LinePusherTest {
  @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.6");

  private static final Instant NOV = Instant.parse("2026-11-01T00:00:00Z");
  private static PGSimpleDataSource ds;

  /** A Stripe stand-in that honours idempotency keys, as the real API does. */
  private final Map<String, String> itemsByKey = new HashMap<>();

  private final List<String> calls = new ArrayList<>();
  private final LinePusher.Stripe stripe =
      (key, org, cents, description) -> {
        calls.add(key + " " + cents);
        return itemsByKey.computeIfAbsent(key, k -> "ii_" + itemsByKey.size());
      };

  @BeforeAll
  static void migrate() {
    ds = new PGSimpleDataSource();
    ds.setUrl(PG.getJdbcUrl());
    ds.setUser(PG.getUsername());
    ds.setPassword(PG.getPassword());
    InvoicingMigrations.migrate(ds);
  }

  @Test
  void reRunningCloseCreatesNoDuplicateStripeLines() throws Exception {
    LinePusher pusher = new LinePusher(ds, stripe);
    LinePusher.Line line =
        new LinePusher.Line(
            "org_acme", "sku_out", NOV, 1, 48_000, "40,000,000 output tokens at $12.00/M");
    String first = pusher.push(line);
    assertThat(pusher.push(line)).as("second close run").isEqualTo(first);
    assertThat(calls).hasSize(1).first().asString().endsWith(" 48000");

    // A re-rating is a new ledger version: a new key, a new line.
    pusher.push(
        new LinePusher.Line("org_acme", "sku_out", NOV, 2, -9_600, "prior-period adjustment"));
    assertThat(calls).hasSize(2);
  }

  @Test
  void keysAreTheHashOfOrgSkuPeriodAndVersion() {
    String k = LinePusher.key("org_acme", "sku_out", NOV, 1);
    assertThat(k).hasSize(64).matches("[0-9a-f]+");
    assertThat(LinePusher.key("org_acme", "sku_out", NOV, 1)).isEqualTo(k);
    assertThat(LinePusher.key("org_acme", "sku_out", NOV, 2)).isNotEqualTo(k);
  }
}
