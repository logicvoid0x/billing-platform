package com.logicvoid0x.billing.usagereplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.RequestUsage;
import com.logicvoid0x.billing.rating.CatalogRatingLibrary;
import com.logicvoid0x.billing.rating.RatingLibrary;
import com.logicvoid0x.billing.rating.golden.GoldenCases;
import com.logicvoid0x.billing.usageaggregator.RatingSource;
import com.logicvoid0x.billing.usageaggregator.sink.Jdbc;
import com.logicvoid0x.billing.usageaggregator.sink.MeteringMigrations;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.test.junit5.MiniClusterExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Phase 6 "Done when": replaying the same hours twice yields identical tables, and a replay after a
 * deliberately corrupted hour restores the correct totals. The archive holds duplicates, as
 * at-least-once delivery leaves them.
 */
@Testcontainers
class UsageReplayJobTest {
  @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.6");

  @RegisterExtension
  static final MiniClusterExtension FLINK =
      new MiniClusterExtension(
          new MiniClusterResourceConfiguration.Builder()
              .setNumberTaskManagers(1)
              .setNumberSlotsPerTaskManager(2)
              .build());

  private static final Instant H10 = Instant.parse("2026-11-01T10:00:00Z");
  private static final Instant H11 = Instant.parse("2026-11-01T11:00:00Z");
  private static final Instant H12 = Instant.parse("2026-11-01T12:00:00Z");
  private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
  private static Jdbc jdbc;

  /** The golden catalog, rebuilt on each task. */
  static final class Golden implements RatingSource {
    private static final long serialVersionUID = 1L;
    private transient RatingLibrary library;

    @Override
    public void open() {
      library = new CatalogRatingLibrary(GoldenCases.catalog(), "test");
    }

    @Override
    public RatingLibrary at(Instant eventTime) {
      return library;
    }
  }

  @BeforeAll
  static void setUp() {
    jdbc = new Jdbc(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    MeteringMigrations.migrate(jdbc);
  }

  private static RequestUsage usage(int i, Instant hour) {
    return new RequestUsage(
        "req_" + hour.getEpochSecond() + "_" + i,
        "org_" + (i % 3),
        "ws_1",
        "key_1",
        i % 2 == 0 ? "model-x" : "model-t",
        "standard",
        "host-a",
        hour.plusSeconds(17L * i),
        Map.of(MeterCode.OUTPUT_TOKENS, 100L + i));
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

  private static void exec(String sql) throws SQLException {
    try (Connection c = jdbc.connect();
        Statement s = c.createStatement()) {
      s.executeUpdate(sql);
    }
  }

  private static int replay(String id) throws Exception {
    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
    env.setParallelism(2);
    return UsageReplayJob.replay(
        env,
        new ArchiveFixture.Loader(),
        new TreeSet<>(List.of(H10, H11)),
        NOW,
        new Golden(),
        jdbc,
        id);
  }

  @Test
  void replayRestoresACorruptedHourAndIsIdenticalWhenRepeated() throws Exception {
    ArchiveFixture.create();
    List<RequestUsage> all = new ArrayList<>();
    for (Instant hour : List.of(H10, H11, H12)) {
      for (int i = 0; i < 60; i++) {
        all.add(usage(i, hour));
      }
    }
    ArchiveFixture.append(all);
    ArchiveFixture.append(all.subList(0, 25)); // redelivered copies, archived again

    // Expected totals for the replayed hours, from the deduplicated input.
    Map<String, String> expected = new TreeMap<>();
    Map<String, long[]> sums = new TreeMap<>();
    for (RequestUsage u : all) {
      if (u.eventHour().equals(H12)) {
        continue;
      }
      String sku = u.model().equals("model-x") ? "sku_out" : "sku_tiered";
      long[] s =
          sums.computeIfAbsent(u.orgId() + "|" + sku + "|" + u.eventHour(), k -> new long[2]);
      s[0] += u.quantities().get(MeterCode.OUTPUT_TOKENS);
      s[1]++;
    }
    sums.forEach((k, v) -> expected.put(k, v[0] + "/" + v[1]));

    // Live table: hour 10 corrupted, plus an hour-12 row the replay must not touch.
    exec("TRUNCATE metering.usage_hourly, metering.usage_hourly_rebuild");
    exec(
        "INSERT INTO metering.usage_hourly (org_id, sku_id, event_hour, quantity, lines) VALUES"
            + " ('org_0', 'sku_out', '"
            + H10
            + "', 999999, 99), ('org_9', 'sku_out', '"
            + H12
            + "', 1, 1)");

    assertThat(replay("replay-1")).isEqualTo(expected.size());
    Map<String, String> once = usageHourly();
    Map<String, String> wanted = new TreeMap<>(expected);
    wanted.put("org_9|sku_out|" + H12, "1/1");
    assertThat(once).isEqualTo(wanted);

    replay("replay-2");
    assertThat(usageHourly()).as("replaying the same hours again").isEqualTo(once);
  }

  @Test
  void hoursInsideTheAllowedLatenessAreRefused() {
    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
    assertThatThrownBy(() ->
            UsageReplayJob.replay(
                env,
                new ArchiveFixture.Loader(),
                new TreeSet<>(List.of(H12)),
                H12.plusSeconds(3600),
                new Golden(),
                jdbc,
                "too-early"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("inside the allowed lateness");
  }
}
