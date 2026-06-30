package com.logicvoid0x.billing.catalog.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The catalog schema against a real Postgres: immutability, overlaps, hour alignment, types. */
@Testcontainers
class CatalogSchemaTest {
  @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.6");

  private static PGSimpleDataSource ds;
  private static int run;
  private String p; // per-test prefix, so tests never share rows (rows cannot be deleted)

  @BeforeAll
  static void migrate() {
    ds = new PGSimpleDataSource();
    ds.setUrl(PG.getJdbcUrl());
    ds.setUser(PG.getUsername());
    ds.setPassword(PG.getPassword());
    assertThat(CatalogMigrations.migrate(ds).success).isTrue();
  }

  @BeforeEach
  void prefix() {
    run++;
    p = "t" + run + "_";
  }

  @Test
  void migrationIsRepeatable() {
    assertThat(CatalogMigrations.migrate(ds).migrationsExecuted).isZero();
  }

  @Test
  void skuEditsAreRejected() throws SQLException {
    insertSku(p + "sku_out", "output_tokens", "2026-01-01T00:00:00Z", null);

    assertRejected(
        "UPDATE catalog.sku SET model = 'other' WHERE sku_id = '" + p + "sku_out'", "never edited");
    assertRejected(
        "UPDATE catalog.sku SET effective_from = '2025-01-01T00:00:00Z' WHERE sku_id = '"
            + p
            + "sku_out'",
        "never edited");
    assertRejected("DELETE FROM catalog.sku WHERE sku_id = '" + p + "sku_out'", "never deleted");
    assertRejected("TRUNCATE catalog.sku CASCADE", "never edited or deleted");
  }

  @Test
  void anOpenPeriodCanBeClosedOnceAndNotRetroactively() throws SQLException {
    insertSku(p + "sku_a", "output_tokens", "2026-01-01T00:00:00Z", null);
    String nextHour = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS) + "";

    assertRejected(
        "UPDATE catalog.sku SET effective_until = '2026-02-01T00:00:00Z' WHERE sku_id = '"
            + p
            + "sku_a'",
        "before the current hour");
    exec(
        "UPDATE catalog.sku SET effective_until = '"
            + nextHour
            + "' WHERE sku_id = '"
            + p
            + "sku_a'");
    assertRejected(
        "UPDATE catalog.sku SET effective_until = '2099-01-01T00:00:00Z' WHERE sku_id = '"
            + p
            + "sku_a'",
        "only an open-ended period");
  }

  @Test
  void overlappingSkuDimensionsAreRejected() throws SQLException {
    insertSku(p + "v1", "output_tokens", "2026-01-01T00:00:00Z", "2026-06-01T00:00:00Z");
    // Same dimensions, adjacent period: allowed (half-open periods).
    insertSku(p + "v2", "output_tokens", "2026-06-01T00:00:00Z", null);
    assertThatThrownBy(() -> insertSku(p + "v3", "output_tokens", "2026-05-01T00:00:00Z", null))
        .hasMessageContaining("conflicting key value violates exclusion constraint");
  }

  @Test
  void pricesFollowEachOtherAsNewRowsOnHourBoundaries() throws SQLException {
    insertSku(p + "sku", "output_tokens", "2026-01-01T00:00:00Z", null);
    insertListPrice(p + "sku", "2026-01-01T00:00:00Z", "2026-03-01T00:00:00Z", 1500, 1_000_000);
    insertListPrice(p + "sku", "2026-03-01T00:00:00Z", null, 1200, 1_000_000);

    assertThatThrownBy(() -> insertListPrice(p + "sku", "2026-02-01T00:00:00Z", null, 1, 1_000_000))
        .hasMessageContaining("exclusion constraint");
    assertThatThrownBy(() -> insertListPrice(p + "sku", "2027-01-01T00:30:00Z", null, 1, 1_000_000))
        .hasMessageContaining("list_price_effective_from_check");
    assertRejected(
        "UPDATE catalog.list_price_tier SET rate_cents = 1 WHERE sku_id = '" + p + "sku'",
        "never edited or deleted");
    assertRejected(
        "UPDATE catalog.list_price SET price_book_version = 9 WHERE sku_id = '" + p + "sku'",
        "never edited");
  }

  @Test
  void writesAreIdempotentOnTheirBusinessKey() throws SQLException {
    insertSku(p + "sku", "input_tokens", "2026-01-01T00:00:00Z", null);
    String sql =
        "INSERT INTO catalog.allowance_weight (sku_id, effective_from, units, per_tokens)"
            + " VALUES ('"
            + p
            + "sku', '2026-01-01T00:00:00Z', 3, 1) ON CONFLICT DO NOTHING";
    assertThat(update(sql)).isEqualTo(1);
    assertThat(update(sql)).as("re-sent write").isZero();
    assertThat(
            update(
                "INSERT INTO catalog.contract_price (org_id, sku_id, effective_from,"
                    + " contract_version) VALUES ('org_acme', '"
                    + p
                    + "sku', '2026-01-01T00:00:00Z', 3) ON CONFLICT DO NOTHING"))
        .isEqualTo(1);
  }

  @Test
  void bandRulesNeedTwoDistinctBandsAndAPositiveThreshold() {
    assertThatThrownBy(() ->
            exec(
                "INSERT INTO catalog.context_band_rule (model, effective_from,"
                    + " threshold_tokens, base_band, above_band) VALUES ('"
                    + p
                    + "m', '2026-01-01T00:00:00Z', 200000, 'base', 'base')"))
        .hasMessageContaining("check constraint");
    assertThatThrownBy(() ->
            exec(
                "INSERT INTO catalog.context_band_rule (model, effective_from,"
                    + " threshold_tokens, base_band, above_band) VALUES ('"
                    + p
                    + "m', '2026-01-01T00:00:00Z', 0, 'base', 'long')"))
        .hasMessageContaining("threshold_tokens_check");
  }

  @Test
  void meterCodesMustBePermanentLowercaseCodes() {
    assertThatThrownBy(() -> insertSku(p + "bad", "OUTPUT_TOKENS", "2026-01-01T00:00:00Z", null))
        .hasMessageContaining("meter_code_check");
  }

  @Test
  void noInexactOrNarrowMoneyColumns() throws SQLException {
    List<String> bad = new ArrayList<>();
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT table_name, column_name, data_type FROM information_schema.columns"
                    + " WHERE table_schema = 'catalog'"
                    + " AND table_name <> 'flyway_schema_history'")) {
      while (rs.next()) {
        String type = rs.getString(3);
        String column = rs.getString(2);
        boolean inexact =
            type.equals("numeric")
                || type.equals("real")
                || type.equals("double precision")
                || type.equals("money");
        boolean narrowMoney =
            (column.endsWith("cents") || column.equals("units") || column.startsWith("per_"))
                && !type.equals("bigint");
        if (inexact || narrowMoney) {
          bad.add(rs.getString(1) + "." + column + " " + type);
        }
      }
    }
    assertThat(bad).isEmpty();
  }

  // ---------------------------------------------------------------- helpers

  private void insertSku(String skuId, String meter, String from, String until)
      throws SQLException {
    exec(
        "INSERT INTO catalog.sku (sku_id, sku_key, meter_code, model, service_tier, context_band,"
            + " effective_from, effective_until) VALUES ('"
            + skuId
            + "', '"
            + skuId
            + "/key', '"
            + meter
            + "', '"
            + p
            + "model', 'standard', 'base', '"
            + from
            + "', "
            + (until == null ? "NULL" : "'" + until + "'")
            + ")");
  }

  private void insertListPrice(String skuId, String from, String until, long cents, long per)
      throws SQLException {
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(false);
      try (Statement s = c.createStatement()) {
        s.executeUpdate(
            "INSERT INTO catalog.list_price (sku_id, effective_from, effective_until,"
                + " price_book_version) VALUES ('"
                + skuId
                + "', '"
                + from
                + "', "
                + (until == null ? "NULL" : "'" + until + "'")
                + ", 1)");
        s.executeUpdate(
            "INSERT INTO catalog.list_price_tier (sku_id, effective_from, tier_no, up_to,"
                + " rate_cents, per_units) VALUES ('"
                + skuId
                + "', '"
                + from
                + "', 1, NULL, "
                + cents
                + ", "
                + per
                + ")");
        c.commit();
      } catch (SQLException e) {
        c.rollback();
        throw e;
      }
    }
  }

  private void assertRejected(String sql, String message) {
    assertThatThrownBy(() -> exec(sql))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining(message);
  }

  private void exec(String sql) throws SQLException {
    update(sql);
  }

  private int update(String sql) throws SQLException {
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement()) {
      return s.executeUpdate(sql);
    }
  }
}
