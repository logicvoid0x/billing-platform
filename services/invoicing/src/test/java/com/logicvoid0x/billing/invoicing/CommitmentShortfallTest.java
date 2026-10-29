package com.logicvoid0x.billing.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The unused part of a commitment is billed at close (Q8 follow-up). */
@Testcontainers
class CommitmentShortfallTest {
  @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.6");

  private static final Instant NOV = Instant.parse("2026-11-01T00:00:00Z");
  private static final Instant CLOSE = Instant.parse("2026-12-01T06:00:00Z"); // before the freeze
  private static PGSimpleDataSource ds;

  /** Stripe stand-in: items by idempotency key, as the real API keeps them. */
  private final Map<String, Long> stripeItems = new LinkedHashMap<>();

  private final LinePusher pusher =
      new LinePusher(
          dataSource(),
          (key, org, cents, description) -> {
            stripeItems.putIfAbsent(key, cents);
            return "ii_" + key.substring(0, 8);
          });

  @BeforeAll
  static void migrate() {
    InvoicingMigrations.migrate(dataSource());
  }

  private static synchronized PGSimpleDataSource dataSource() {
    if (ds == null) {
      ds = new PGSimpleDataSource();
      ds.setUrl(PG.getJdbcUrl());
      ds.setUser(PG.getUsername());
      ds.setPassword(PG.getPassword());
    }
    return ds;
  }

  private Optional<LinePusher.Line> close(String org, long commit, long drawdown, int version)
      throws Exception {
    return CommitmentShortfall.close(pusher, org, NOV, commit, drawdown, version, CLOSE);
  }

  @Test
  void theShortfallIsTheCommitmentMinusDrawdown() {
    assertThat(CommitmentShortfall.amount(10_000, 6_000)).isEqualTo(4_000);
    assertThat(CommitmentShortfall.amount(10_000, 10_000)).isZero();
    assertThat(CommitmentShortfall.amount(10_000, 0)).isEqualTo(10_000);
    assertThatThrownBy(() -> CommitmentShortfall.amount(10_000, 10_001))
        .as("drawdown never exceeds the commitment")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CommitmentShortfall.amount(0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void usageBelowTheCommitmentAddsOneShortfallLine() throws Exception {
    Optional<LinePusher.Line> line = close("org_short", 10_000, 6_000, 1);

    assertThat(line).isPresent();
    assertThat(line.get().skuId()).isEqualTo(CommitmentShortfall.SKU);
    assertThat(line.get().amountCents()).isEqualTo(4_000);
    assertThat(stripeItems)
        .containsExactly(
            Map.entry(LinePusher.key("org_short", CommitmentShortfall.SKU, NOV, 1), 4_000L));
  }

  @Test
  void noLineWhenUsageCoversTheCommitment() throws Exception {
    assertThat(close("org_covered", 10_000, 10_000, 1)).isEmpty();
    assertThat(stripeItems).isEmpty();
  }

  @Test
  void reClosingAddsNoDuplicate() throws Exception {
    close("org_twice", 10_000, 6_000, 1);
    close("org_twice", 10_000, 6_000, 1);
    assertThat(stripeItems).hasSize(1);
    assertThat(pusher.pushedTotal("org_twice", CommitmentShortfall.SKU, NOV)).isEqualTo(4_000);
  }

  @Test
  void aCorrectionPushesOnlyTheDifference() throws Exception {
    close("org_fix", 10_000, 6_000, 1); // 4,000 short

    Optional<LinePusher.Line> fix = close("org_fix", 10_000, 7_000, 2); // usage was higher
    assertThat(fix).map(LinePusher.Line::amountCents).contains(-1_000L);
    assertThat(pusher.pushedTotal("org_fix", CommitmentShortfall.SKU, NOV)).isEqualTo(3_000);

    Optional<LinePusher.Line> covered = close("org_fix", 10_000, 10_000, 3);
    assertThat(covered).map(LinePusher.Line::amountCents).contains(-3_000L);
    assertThat(pusher.pushedTotal("org_fix", CommitmentShortfall.SKU, NOV)).isZero();

    assertThat(close("org_fix", 10_000, 10_000, 4)).as("nothing left to correct").isEmpty();
    assertThat(stripeItems).hasSize(3);
  }

  @Test
  void aCorrectionAfterTheFreezeIsAPriorPeriodAdjustment() throws Exception {
    close("org_late", 10_000, 6_000, 1);
    Instant afterFreeze = Instant.parse("2026-12-05T00:00:00Z");
    Optional<LinePusher.Line> late =
        CommitmentShortfall.close(pusher, "org_late", NOV, 10_000, 8_000, 2, afterFreeze);
    assertThat(late).isPresent();
    assertThat(late.get().amountCents()).isEqualTo(-2_000);
    assertThat(late.get().description()).contains("prior-period adjustment");
  }
}
