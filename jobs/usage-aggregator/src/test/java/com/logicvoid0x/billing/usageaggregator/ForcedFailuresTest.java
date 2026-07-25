package com.logicvoid0x.billing.usageaggregator;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.contracts.codec.UsageEventCodec;
import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.MeteredLine;
import com.logicvoid0x.billing.domain.RequestUsage;
import com.logicvoid0x.billing.usageaggregator.sink.Jdbc;
import com.logicvoid0x.billing.usageaggregator.sink.MeteringMigrations;
import com.logicvoid0x.billing.usageaggregator.sink.UsageHourlySink;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.configuration.ConfigOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestartStrategyOptions;
import org.apache.flink.core.execution.CheckpointingMode;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.test.junit5.MiniClusterExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Phase 5 "Done when": the whole aggregator (resolve, dedupe, hourly windows, checkpoint-aligned
 * sink) runs on a MiniCluster with duplicated input and three forced task failures at different
 * stages, and {@code usage_hourly} ends up equal to the deduplicated input with nothing counted
 * twice.
 */
@Testcontainers
class ForcedFailuresTest {
  @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.6");

  @RegisterExtension
  static final MiniClusterExtension FLINK =
      new MiniClusterExtension(
          new MiniClusterResourceConfiguration.Builder()
              .setNumberTaskManagers(1)
              .setNumberSlotsPerTaskManager(4)
              .build());

  private static final Instant H10 = Instant.parse("2026-11-01T10:00:00Z");

  /** Failure points that have already thrown (static: the MiniCluster runs in this JVM). */
  static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

  /** Passes elements through; throws once, after {@code after} elements, the first time only. */
  static final class FailOnce<T> extends RichMapFunction<T, T> {
    private static final long serialVersionUID = 1L;
    private final String point;
    private final int after;
    private final long sleepMicros;
    private transient int seen;

    FailOnce(String point, int after, long sleepMicros) {
      this.point = point;
      this.after = after;
      this.sleepMicros = sleepMicros;
    }

    @Override
    public void open(OpenContext context) {
      seen = 0;
    }

    @Override
    public T map(T value) throws Exception {
      if (sleepMicros > 0) {
        Thread.sleep(0, (int) Math.min(999_999, sleepMicros * 1000)); // pace the stream
        if (sleepMicros >= 1000) {
          Thread.sleep(sleepMicros / 1000);
        }
      }
      if (++seen == after && FAILED.add(point)) {
        throw new IllegalStateException("forced failure at " + point);
      }
      return value;
    }
  }

  private static RequestUsage usage(int i) {
    String model = i % 3 == 0 ? "model-t" : "model-x";
    return new RequestUsage(
        "req_" + i,
        "org_" + (i % 4),
        "ws_1",
        "key_1",
        model,
        "standard",
        "host-" + (i % 2),
        H10.plusSeconds(4L * i), // ~3.3 h of event time, in order
        Map.of(MeterCode.OUTPUT_TOKENS, 1_000L + i));
  }

  @Test
  void totalsEqualTheInputAcrossThreeForcedFailures() throws Exception {
    Jdbc jdbc = new Jdbc(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    MeteringMigrations.migrate(jdbc);
    FAILED.clear();

    int requests = 3_000;
    List<RawUsage> input = new ArrayList<>();
    Map<String, Long> expected = new TreeMap<>();
    for (int i = 0; i < requests; i++) {
      RequestUsage u = usage(i);
      byte[] bytes = UsageEventCodec.encode(u);
      input.add(new RawUsage(bytes, "usage.raw", 0, i));
      if (i % 7 == 0) {
        input.add(new RawUsage(bytes, "usage.raw", 1, i)); // redelivered copy
      }
      String sku = u.model().equals("model-t") ? "sku_tiered" : "sku_out";
      expected.merge(u.orgId() + "|" + sku + "|" + u.eventHour(), 1_000L + i, Long::sum);
    }

    Configuration config = UsageAggregatorJob.stateConfig();
    // RocksDB paths under the JUnit temp dir exceed the Windows path limit (247): use a short one.
    config.set(
        ConfigOptions.key("state.backend.rocksdb.localdir").stringType().noDefaultValue(),
        java.nio.file.Path.of("build", "rdb").toAbsolutePath().toString());
    config.set(RestartStrategyOptions.RESTART_STRATEGY, "fixed-delay");
    config.set(RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_ATTEMPTS, 10);
    config.set(RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_DELAY, Duration.ofMillis(200));
    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(config);
    env.setParallelism(2);
    env.enableCheckpointing(100, CheckpointingMode.EXACTLY_ONCE);

    DataStream<RawUsage> raw =
        env.fromData(input).map(new FailOnce<RawUsage>("source", 1_000, 500)).name("fail-source");
    DataStream<MeteredLine> lines =
        UsageAggregatorJob.resolve(raw, new ResolveUsageTest.GoldenRatingSource());
    DataStream<MeteredLine> unique =
        UsageAggregatorJob.dedupe(lines)
            .map(new FailOnce<MeteredLine>("dedupe", 900, 0))
            .name("fail-dedupe");
    DataStream<HourlyUsage> hourly =
        HourlyWindows.apply(unique, HourlyWindows.watermarks())
            .map(new FailOnce<HourlyUsage>("windows", 3, 0))
            .name("fail-windows");
    hourly.sinkTo(new UsageHourlySink("forced-failures", jdbc)).name("usage-hourly");
    env.execute("forced-failures");

    assertThat(FAILED).containsExactlyInAnyOrder("source", "dedupe", "windows");

    Map<String, Long> actual = new TreeMap<>();
    long lineCount = 0;
    int batches;
    try (Connection c = jdbc.connect();
        Statement s = c.createStatement()) {
      try (ResultSet rs =
          s.executeQuery(
              "SELECT org_id, sku_id, event_hour, quantity, lines FROM metering.usage_hourly")) {
        while (rs.next()) {
          actual.put(
              rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getTimestamp(3).toInstant(),
              rs.getLong(4));
          lineCount += rs.getLong(5);
        }
      }
      try (ResultSet rs = s.executeQuery("SELECT count(*) FROM metering.applied_batches")) {
        rs.next();
        batches = rs.getInt(1);
      }
    }
    assertThat(actual).isEqualTo(expected);
    assertThat(lineCount).as("each request counted once").isEqualTo(requests);
    assertThat(batches).as("several checkpoint batches").isGreaterThan(1);
  }
}
