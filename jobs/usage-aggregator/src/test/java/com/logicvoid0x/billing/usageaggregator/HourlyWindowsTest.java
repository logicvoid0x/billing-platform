package com.logicvoid0x.billing.usageaggregator;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.contracts.v1.UsageCorrection;
import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.MeteredLine;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.flink.api.common.eventtime.Watermark;
import org.apache.flink.api.common.eventtime.WatermarkGenerator;
import org.apache.flink.api.common.eventtime.WatermarkOutput;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.test.junit5.MiniClusterExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class HourlyWindowsTest {
  @RegisterExtension
  static final MiniClusterExtension FLINK =
      new MiniClusterExtension(
          new MiniClusterResourceConfiguration.Builder()
              .setNumberTaskManagers(1)
              .setNumberSlotsPerTaskManager(2)
              .build());

  /** A watermark after every line (event time - 5 min), so the test controls lateness exactly. */
  static final class EveryLine implements WatermarkGenerator<MeteredLine> {
    @Override
    public void onEvent(MeteredLine line, long ts, WatermarkOutput out) {
      out.emitWatermark(new Watermark(ts - Duration.ofMinutes(5).toMillis()));
    }

    @Override
    public void onPeriodicEmit(WatermarkOutput out) {}
  }

  private static MeteredLine line(String id, String org, Instant at, long quantity) {
    return new MeteredLine(id, org, "sku_out", MeterCode.OUTPUT_TOKENS, quantity, at);
  }

  private static Instant at(String hhmm) {
    return Instant.parse("2026-11-01T" + hhmm + ":00Z");
  }

  @Test
  void hoursFireOnceThenEmitOnlyIncrementsForLateLinesAndTooLateLinesBecomeCorrections()
      throws Exception {
    List<MeteredLine> input =
        List.of(
            line("a", "org_1", at("10:05"), 100),
            line("b", "org_1", at("10:50"), 20),
            line("c", "org_2", at("10:30"), 7),
            line("d", "org_1", at("11:10"), 5),
            line("e", "org_1", at("11:20"), 1), // watermark 11:15: hour 10 fires (120, 2)
            line("f", "org_1", at("10:40"), 3), // late, within 1 h: increment (3, 1) for hour 10
            line("g", "org_1", at("12:30"), 9), // watermark 12:25: hour 11 fires; hour 10 closed
            line("h", "org_1", at("10:59"), 50), // past 11:00 + 1 h lateness: a correction
            line("i", "org_1", at("11:30"), 2)); // late for hour 11, within lateness

    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
    env.setParallelism(1);
    DataStream<MeteredLine> lines = env.fromData(input);
    SingleOutputStreamOperator<HourlyUsage> hourly =
        HourlyWindows.apply(
            lines,
            WatermarkStrategy.<MeteredLine>forGenerator(c -> new EveryLine())
                .withTimestampAssigner((l, p) -> l.eventTime().toEpochMilli()));
    DataStream<String> out =
        hourly
            .map(h ->
                "hour "
                    + h.orgId()
                    + " "
                    + h.eventHour().toString().substring(11, 16)
                    + " +"
                    + h.quantity()
                    + "/"
                    + h.lines())
            .returns(Types.STRING)
            .union(
                hourly
                    .getSideOutput(HourlyWindows.TOO_LATE)
                    .map(l -> {
                      UsageCorrection c = HourlyWindows.correction(l);
                      return "correction "
                          + c.getRequestId()
                          + " "
                          + c.getQuantity()
                          + " "
                          + c.getReason();
                    })
                    .returns(Types.STRING));

    List<String> results = new ArrayList<>();
    out.executeAndCollect().forEachRemaining(results::add);

    assertThat(results)
        .containsExactlyInAnyOrder(
            "hour org_1 10:00 +120/2",
            "hour org_2 10:00 +7/1",
            "hour org_1 10:00 +3/1",
            "hour org_1 11:00 +6/2",
            "hour org_1 11:00 +2/1",
            "hour org_1 12:00 +9/1", // fires at end of input
            "correction h 50 " + HourlyWindows.LATE_REASON);

    // Increments sum to the hour totals of everything that was not too late.
    long hour10 =
        results.stream()
            .filter(r -> r.startsWith("hour org_1 10:00"))
            .mapToLong(r -> Long.parseLong(r.substring(r.indexOf('+') + 1, r.indexOf('/'))))
            .sum();
    assertThat(hour10).isEqualTo(100 + 20 + 3);
  }
}
