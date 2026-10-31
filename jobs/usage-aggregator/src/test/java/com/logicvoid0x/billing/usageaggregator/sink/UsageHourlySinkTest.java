package com.logicvoid0x.billing.usageaggregator.sink;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.usageaggregator.HourlyUsage;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.flink.core.execution.CheckpointingMode;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.test.junit5.MiniClusterExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class UsageHourlySinkTest {
  @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.6");

  @RegisterExtension
  static final MiniClusterExtension FLINK =
      new MiniClusterExtension(
          new MiniClusterResourceConfiguration.Builder()
              .setNumberTaskManagers(1)
              .setNumberSlotsPerTaskManager(4)
              .build());

  private static final Instant H10 = Instant.parse("2026-11-01T10:00:00Z");
  private static final Instant H11 = Instant.parse("2026-11-01T11:00:00Z");
  private static Jdbc jdbc;

  @BeforeAll
  static void migrate() {
    jdbc = new Jdbc(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    MeteringMigrations.migrate(jdbc);
  }

  @BeforeEach
  void empty() throws SQLException {
    try (Connection c = jdbc.connect();
        Statement s = c.createStatement()) {
      s.executeUpdate(
          "TRUNCATE metering.usage_hourly, metering.usage_hourly_by_key, metering.applied_batches");
    }
  }

  private static Map<String, String> table() throws SQLException {
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
  void aBatchIsAppliedExactlyOnceAndIncrementsAdd() throws Exception {
    HourlyBatch first =
        new HourlyBatch(
            "job:j1:0:1",
            List.of(
                new HourlyUsage("org_1", "ws_1", "key_1", "sku_out", H10, 100, 2),
                new HourlyUsage("org_1", "ws_1", "key_1", "sku_in", H10, 7, 1)));
    assertThat(UsageHourlySink.apply(jdbc, first)).isTrue();
    assertThat(UsageHourlySink.apply(jdbc, first)).as("re-commit after recovery").isFalse();
    assertThat(
            UsageHourlySink.apply(
                jdbc,
                new HourlyBatch(
                    "job:j1:0:2",
                    List.of(new HourlyUsage("org_1", "ws_1", "key_1", "sku_out", H10, 5, 1)))))
        .isTrue();
    assertThat(table())
        .containsExactly(
            Map.entry("org_1|sku_in|" + H10, "7/1"), Map.entry("org_1|sku_out|" + H10, "105/3"));
  }

  @Test
  void theSerializerRoundTripsABatch() throws Exception {
    HourlyBatch batch =
        new HourlyBatch(
            "job:j1:3:9",
            List.of(new HourlyUsage("org_9", "ws_1", "key_1", "sku_x", H11, Long.MAX_VALUE, 4)));
    HourlyBatch.Serializer s = new HourlyBatch.Serializer();
    assertThat(s.deserialize(s.getVersion(), s.serialize(batch))).isEqualTo(batch);
  }

  @Test
  void aCheckpointedStreamLandsInUsageHourlyWithTotalsEqualToTheInput() throws Exception {
    List<HourlyUsage> input = new ArrayList<>();
    long expected10 = 0;
    for (int i = 0; i < 2_000; i++) {
      Instant hour = i % 2 == 0 ? H10 : H11;
      input.add(new HourlyUsage("org_" + (i % 5), "ws_1", "key_1", "sku_out", hour, i + 1, 1));
      if (i % 5 == 0 && hour.equals(H10)) {
        expected10 += i + 1;
      }
    }
    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
    env.setParallelism(3);
    env.enableCheckpointing(50, CheckpointingMode.EXACTLY_ONCE);
    env.fromData(input).rebalance().sinkTo(new UsageHourlySink("test", jdbc)).name("usage-hourly");
    env.execute("sink-test");

    Map<String, String> rows = table();
    assertThat(rows).hasSize(10);
    assertThat(rows.get("org_0|sku_out|" + H10)).isEqualTo(expected10 + "/200");
    long total = 0;
    long lines = 0;
    for (String v : rows.values()) {
      total += Long.parseLong(v.substring(0, v.indexOf('/')));
      lines += Long.parseLong(v.substring(v.indexOf('/') + 1));
    }
    assertThat(total).isEqualTo(2_000L * 2_001 / 2);
    assertThat(lines).isEqualTo(2_000);
  }

  @Test
  void byKeyRowsSumOverWorkspaceAndKeyToUsageHourly() throws Exception {
    List<HourlyUsage> input = new ArrayList<>();
    for (int i = 0; i < 3_000; i++) {
      input.add(
          new HourlyUsage(
              "org_" + (i % 3),
              "ws_" + (i % 4),
              "key_" + (i % 7),
              i % 2 == 0 ? "sku_out" : "sku_in",
              i % 5 == 0 ? H11 : H10,
              i + 1,
              1));
    }
    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
    env.setParallelism(3);
    env.enableCheckpointing(50, CheckpointingMode.EXACTLY_ONCE);
    env.fromData(input).rebalance().sinkTo(new UsageHourlySink("test", jdbc)).name("usage-hourly");
    env.execute("by-key-test");

    Map<String, String> summed = new TreeMap<>();
    try (Connection c = jdbc.connect();
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT org_id, sku_id, event_hour, sum(quantity), sum(lines)"
                    + " FROM metering.usage_hourly_by_key GROUP BY org_id, sku_id, event_hour")) {
      while (rs.next()) {
        summed.put(
            rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getTimestamp(3).toInstant(),
            rs.getLong(4) + "/" + rs.getLong(5));
      }
    }
    assertThat(summed).isNotEmpty().isEqualTo(table());
    try (Connection c = jdbc.connect();
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT count(DISTINCT (workspace_id, api_key_id)) FROM"
                    + " metering.usage_hourly_by_key")) {
      rs.next();
      assertThat(rs.getLong(1)).as("workspace and key kept").isEqualTo(28);
    }
  }

  @Test
  void versionOneBatchesStillDeserialize() throws Exception {
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    try (java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
      out.writeUTF("job:j0:0:1");
      out.writeInt(1);
      out.writeUTF("org_1");
      out.writeUTF("sku_out");
      out.writeLong(H10.getEpochSecond());
      out.writeLong(42);
      out.writeLong(1);
    }
    HourlyBatch b = new HourlyBatch.Serializer().deserialize(1, bytes.toByteArray());
    assertThat(b.increments())
        .containsExactly(
            new HourlyUsage(
                "org_1",
                HourlyBatch.UNATTRIBUTED,
                HourlyBatch.UNATTRIBUTED,
                "sku_out",
                H10,
                42,
                1));
  }
}
