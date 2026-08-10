package com.logicvoid0x.billing.ratingengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicvoid0x.billing.rating.CatalogRatingLibrary;
import com.logicvoid0x.billing.rating.RatingLibrary;
import com.logicvoid0x.billing.rating.golden.GoldenCases;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class HourlyRaterTest {
  @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.6");

  private static final Instant H10 = Instant.parse("2026-11-01T10:00:00Z");
  private static final Instant H11 = Instant.parse("2026-11-01T11:00:00Z");
  private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
  private static final RatingLibrary GOLDEN =
      new CatalogRatingLibrary(GoldenCases.catalog(), "rating-test");

  private static PGSimpleDataSource ds;
  private HourlyRater rater;

  @BeforeAll
  static void schema() throws SQLException {
    ds = new PGSimpleDataSource();
    ds.setUrl(PG.getJdbcUrl());
    ds.setUser(PG.getUsername());
    ds.setPassword(PG.getPassword());
    RatingMigrations.migrate(ds);
    // metering.usage_hourly is owned by jobs:usage-aggregator (metering V1); same columns here.
    exec(
        "CREATE SCHEMA IF NOT EXISTS metering; CREATE TABLE IF NOT EXISTS metering.usage_hourly ("
            + " org_id text NOT NULL, sku_id text NOT NULL, event_hour timestamptz NOT NULL,"
            + " quantity bigint NOT NULL, lines bigint NOT NULL,"
            + " PRIMARY KEY (org_id, sku_id, event_hour))");
  }

  @BeforeEach
  void setUp() {
    rater = new HourlyRater(ds, hour -> GOLDEN, "rating-code-1");
  }

  private static void exec(String sql) throws SQLException {
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement()) {
      s.execute(sql);
    }
  }

  private static void usage(String org, String sku, Instant hour, long quantity)
      throws SQLException {
    exec(
        "INSERT INTO metering.usage_hourly VALUES ('"
            + org
            + "', '"
            + sku
            + "', '"
            + hour
            + "', "
            + quantity
            + ", 1)");
  }

  private static List<String> ledger(String org) throws SQLException {
    List<String> rows = new ArrayList<>();
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT sku_id, event_hour, rating_version, total_before, quantity, amount_cents,"
                    + " catalog_version, rating_code_version FROM rating.rated_ledger WHERE org_id"
                    + " = '"
                    + org
                    + "' ORDER BY sku_id, event_hour")) {
      while (rs.next()) {
        rows.add(
            rs.getString(1)
                + " "
                + rs.getTimestamp(2).toInstant()
                + " v"
                + rs.getInt(3)
                + " "
                + rs.getLong(4)
                + "+"
                + rs.getLong(5)
                + " = "
                + rs.getLong(6)
                + "c "
                + rs.getString(7)
                + " "
                + rs.getString(8));
      }
    }
    return rows;
  }

  @Test
  void theAcmeHourRatesAtTheContractPriceWithItsVersions() throws Exception {
    usage("org_acme", "sku_out", H10, 40_000_000);
    rater.rateSealedHours(NOW);
    List<String> once = ledger("org_acme");
    assertThat(once)
        .containsExactly(
            "sku_out " + H10 + " v1 0+40000000 = 48000c rating-test/golden-1 rating-code-1");
    rater.rateSealedHours(NOW);
    assertThat(ledger("org_acme")).as("re-run writes nothing").isEqualTo(once);
  }

  @Test
  void hourlyChargesTelescopeOverTheTierBoundary() throws Exception {
    usage("org_tier", "sku_tiered", H10, 990_000_000);
    usage("org_tier", "sku_tiered", H11, 30_000_000);
    rater.rateSealedHours(NOW);
    assertThat(ledger("org_tier"))
        .extracting(r -> r.substring(r.indexOf(" v1 ") + 4, r.indexOf("c ")))
        .containsExactly("0+990000000 = 1485000", "990000000+30000000 = 39000");
    // The period total, rounded once: F(1.02B) = 1B x 15/M + 20M x 12/M.
    assertThat(1_485_000L + 39_000L)
        .isEqualTo(GOLDEN.pricing("org_tier", "sku_tiered", H10).totalCents(1_020_000_000L));
  }

  @Test
  void unsealedHoursWaitAndANewMonthStartsAFreshRunningTotal() throws Exception {
    Instant dec1 = Instant.parse("2026-12-01T00:00:00Z");
    usage("org_month", "sku_tiered", Instant.parse("2026-11-30T23:00:00Z"), 600_000_000);
    usage("org_month", "sku_tiered", dec1, 600_000_000);
    rater.rateSealedHours(dec1.plusSeconds(7200)); // 02:00: Nov 30 23:00 sealed (01:05), Dec 1 not
    assertThat(ledger("org_month")).as("Dec 1 00:00 not sealed yet").hasSize(1);
    rater.rateSealedHours(dec1.plus(HourlyRater.SEAL_DELAY));
    assertThat(ledger("org_month"))
        .extracting(r -> r.substring(r.indexOf(" v1 ") + 4, r.indexOf("c ")))
        .containsExactly("0+600000000 = 900000", "0+600000000 = 900000");
  }

  @Test
  void anHourEarlierThanARatedHourIsRefusedAndTheLedgerIsAppendOnly() throws Exception {
    usage("org_late", "sku_out", H11, 10);
    rater.rateSealedHours(NOW);
    usage("org_late", "sku_out", H10, 5);
    HourlyRater.Result r = rater.rateSealedHours(NOW);
    assertThat(r.blocked())
        .filteredOn(b -> b.startsWith("org_late|sku_out|"))
        .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .contains("re-rate the period");
    assertThat(ledger("org_late")).as("the late hour stays unrated").hasSize(1);
    assertThatThrownBy(
            () -> exec("UPDATE rating.rated_ledger SET amount_cents = 0 WHERE org_id = 'org_late'"))
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> exec("DELETE FROM rating.rated_ledger WHERE org_id = 'org_late'"))
        .hasMessageContaining("append-only");
  }
}
