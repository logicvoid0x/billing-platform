package com.logicvoid0x.billing.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.rating.CatalogRatingLibrary;
import com.logicvoid0x.billing.rating.RatingLibrary;
import com.logicvoid0x.billing.rating.golden.GoldenCases;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Phase 13 "Done when": re-running close for the same period creates no duplicate Stripe lines, and
 * the statement total equals the rated ledger for the period.
 */
@Testcontainers
class PeriodCloseRunTest {
  @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.6");

  private static final Instant NOV = Instant.parse("2026-11-01T00:00:00Z");
  private static final RatingLibrary GOLDEN =
      new CatalogRatingLibrary(GoldenCases.catalog(), "close");
  private static PGSimpleDataSource ds;

  /** Stripe stand-in: items by idempotency key, as the real API keeps them. */
  private final Map<String, Long> stripeItems = new LinkedHashMap<>();

  @BeforeAll
  static void migrate() {
    ds = new PGSimpleDataSource();
    ds.setUrl(PG.getJdbcUrl());
    ds.setUser(PG.getUsername());
    ds.setPassword(PG.getPassword());
    InvoicingMigrations.migrate(ds);
  }

  /** One close run: lines from the rated hourly increments, pushed, read back, statement issued. */
  private PrepaidStatement close(Map<String, List<Long>> hoursBySku, String org) throws Exception {
    LinePusher pusher =
        new LinePusher(
            ds,
            (key, o, cents, description) -> {
              stripeItems.putIfAbsent(key, cents);
              return "ii_" + key.substring(0, 8);
            });
    Map<String, Long> lines = new HashMap<>();
    Map<String, Long> expectedByKey = new HashMap<>();
    for (Map.Entry<String, List<Long>> e : hoursBySku.entrySet()) {
      String sku = e.getKey();
      long total = 0;
      long increments = 0;
      for (long q : e.getValue()) {
        increments += GOLDEN.chargeCents(org, sku, total, total + q, GoldenCases.HOUR);
        total += q;
      }
      long line =
          InvoiceLines.requireEqualsRated(
              InvoiceLines.amount(GOLDEN, org, sku, total, GoldenCases.HOUR), increments);
      pusher.push(new LinePusher.Line(org, sku, NOV, 1, line, total + " units of " + sku));
      lines.put(sku, line);
      expectedByKey.put(LinePusher.key(org, sku, NOV, 1), line);
    }
    // Read the invoice back (the stand-in holds exactly what was pushed) and guard finalizing.
    Map<String, Long> onInvoice = new HashMap<>();
    expectedByKey.keySet().forEach(k -> onInvoice.put(k, stripeItems.get(k)));
    long subtotal = onInvoice.values().stream().mapToLong(Long::longValue).sum();
    FinalizeGuard.requireFinalizable(
        new FinalizeGuard.StripeInvoice(onInvoice, subtotal), expectedByKey);

    long rated = lines.values().stream().mapToLong(Long::longValue).sum();
    return new PrepaidStatement(
            org, NOV, lines, 100_000, 3_000_000, rated, 0, 100_000 + 3_000_000 - rated)
        .requireConsistent();
  }

  @Test
  void reRunningCloseCreatesNoDuplicateLinesAndTheStatementEqualsTheRatedLedger() throws Exception {
    Map<String, List<Long>> usage =
        Map.of(
            "sku_out", List.of(10_000_000L, 5_000_000L, 24_999_999L, 1L), // Acme golden: 48,000
            "sku_tiered", List.of(990_000_000L, 7_000_000L, 23_000_000L)); // tier boundary
    PrepaidStatement first = close(usage, "org_acme");
    int itemsAfterFirst = stripeItems.size();
    PrepaidStatement second = close(usage, "org_acme");

    assertThat(itemsAfterFirst).isEqualTo(2);
    assertThat(stripeItems).as("no duplicate Stripe lines after a second close").hasSize(2);
    assertThat(second).isEqualTo(first);
    assertThat(first.lines()).containsEntry("sku_out", 48_000L);
    assertThat(first.ratedTotal())
        .isEqualTo(
            48_000
                + GOLDEN
                    .pricing("org_acme", "sku_tiered", GoldenCases.HOUR)
                    .totalCents(1_020_000_000L));
  }
}
