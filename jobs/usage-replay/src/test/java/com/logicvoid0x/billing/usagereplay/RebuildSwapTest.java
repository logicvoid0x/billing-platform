package com.logicvoid0x.billing.usagereplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicvoid0x.billing.usageaggregator.HourlyUsage;
import com.logicvoid0x.billing.usageaggregator.sink.Jdbc;
import com.logicvoid0x.billing.usageaggregator.sink.MeteringMigrations;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class RebuildSwapTest {
  @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.6");

  private static final Instant H10 = Instant.parse("2026-11-01T10:00:00Z");
  private static final Instant H11 = Instant.parse("2026-11-01T11:00:00Z");
  private static final Instant H12 = Instant.parse("2026-11-01T12:00:00Z");
  private static Jdbc jdbc;

  @BeforeAll
  static void migrate() {
    jdbc = new Jdbc(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    MeteringMigrations.migrate(jdbc);
  }

  private static void exec(String sql) throws SQLException {
    try (Connection c = jdbc.connect();
        Statement s = c.createStatement()) {
      s.executeUpdate(sql);
    }
  }

  private static Map<String, String> usageHourly() throws SQLException {
    Map<String, String> rows = new TreeMap<>();
    try (Connection c = jdbc.connect();
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT org_id, sku_id, event_hour, quantity, lines FROM metering.usage_hourly")) {
      while (rs.next()) {
        rows.put(
            rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getTimestamp(3).toInstant(),
            rs.getLong(4) + "/" + rs.getLong(5));
      }
    }
    return rows;
  }

  @Test
  void aReplayRestoresACorruptedHourAndReplayingTwiceIsIdentical() throws Exception {
    exec(
        "TRUNCATE metering.usage_hourly, metering.usage_hourly_rebuild,"
            + " metering.usage_hourly_by_key");
    // Live table: hour 10 corrupted (quantity doubled), hour 12 untouched by the replay.
    exec(
        "INSERT INTO metering.usage_hourly (org_id, sku_id, event_hour, quantity, lines) VALUES"
            + " ('org_1', 'sku_out', '"
            + H10
            + "', 240, 4), ('org_1', 'sku_out', '"
            + H12
            + "', 9, 1)");
    SortedSet<Instant> hours = new TreeSet<>(List.of(H10, H11));
    List<HourlyUsage> rebuilt =
        List.of(
            new HourlyUsage("org_1", "ws_1", "key_1", "sku_out", H10, 120, 2),
            new HourlyUsage("org_2", "ws_1", "key_1", "sku_out", H10, 7, 1),
            new HourlyUsage("org_1", "ws_1", "key_1", "sku_out", H11, 6, 2));

    assertThat(RebuildSwap.replace(jdbc, "replay-1", hours, rebuilt)).isEqualTo(3);
    Map<String, String> once = usageHourly();
    assertThat(once)
        .containsExactly(
            Map.entry("org_1|sku_out|" + H10, "120/2"),
            Map.entry("org_1|sku_out|" + H11, "6/2"),
            Map.entry("org_1|sku_out|" + H12, "9/1"),
            Map.entry("org_2|sku_out|" + H10, "7/1"));

    RebuildSwap.replace(jdbc, "replay-2", hours, rebuilt);
    assertThat(usageHourly()).as("second replay of the same hours").isEqualTo(once);
  }

  @Test
  void aFailedSwapLeavesTheLiveTableUntouched() throws Exception {
    exec(
        "TRUNCATE metering.usage_hourly, metering.usage_hourly_rebuild,"
            + " metering.usage_hourly_by_key");
    exec(
        "INSERT INTO metering.usage_hourly (org_id, sku_id, event_hour, quantity, lines) VALUES"
            + " ('org_1', 'sku_out', '"
            + H10
            + "', 5, 1)");
    Map<String, String> before = usageHourly();
    // A blank workspace violates usage_hourly_by_key's check: the swap fails mid-transaction.
    List<HourlyUsage> bad =
        List.of(
            new HourlyUsage("org_1", "ws_1", "key_1", "sku_out", H10, 1, 1),
            new HourlyUsage("org_1", " ", "key_1", "sku_out", H10, 2, 1));
    assertThatThrownBy(() -> RebuildSwap.replace(jdbc, "bad", new TreeSet<>(List.of(H10)), bad))
        .isInstanceOf(SQLException.class);
    assertThat(usageHourly()).isEqualTo(before);
  }

  @Test
  void theGuardRefusesHoursInsideTheAllowedLateness() {
    Instant now = Instant.parse("2026-11-01T13:00:00Z");
    // Hour 10 seals at 12:05; hour 11 at 13:05.
    assertThat(ReplayGuard.requireSealed(List.of(H10), now)).containsExactly(H10);
    assertThatThrownBy(() -> ReplayGuard.requireSealed(List.of(H10, H11), now))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "2026-11-01T11:00:00Z (inside the allowed lateness until 2026-11-01T13:05:00Z)");
    assertThatThrownBy(() -> ReplayGuard.requireSealed(List.of(H10.plusSeconds(60)), now))
        .hasMessageContaining("not an hour boundary");
    assertThatThrownBy(() -> ReplayGuard.requireSealed(List.of(), now))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
