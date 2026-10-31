package com.logicvoid0x.billing.usageaggregator;

import com.google.protobuf.Timestamp;
import com.logicvoid0x.billing.contracts.v1.UsageCorrection;
import com.logicvoid0x.billing.domain.MeteredLine;
import java.time.Duration;
import java.time.Instant;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.datastream.WindowedStream;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

/**
 * Event-time hours per (org, workspace, API key, SKU), with 1 h allowed lateness (design: "Event
 * time; 1 h allowed lateness; later events become correction records").
 *
 * <p>A window fires when the watermark passes the end of its hour, and again for each late line
 * within the lateness. Every firing emits only the increment since the previous one, so the sink
 * can add increments without double counting. Lines later than the lateness go to {@link #TOO_LATE}
 * and become {@code UsageCorrection}s on {@code usage.corrections}.
 */
public final class HourlyWindows {
  /** How late a line may be and still land in its hour. */
  public static final Duration ALLOWED_LATENESS = Duration.ofHours(1);

  /** Default out-of-orderness for watermarks. */
  public static final Duration OUT_OF_ORDERNESS = Duration.ofMinutes(5);

  /** Lines that arrived after their hour closed for good. */
  public static final OutputTag<MeteredLine> TOO_LATE =
      new OutputTag<>("too-late", TypeInformation.of(MeteredLine.class));

  /** Correction reason for lines past the allowed lateness. */
  public static final String LATE_REASON = "late_beyond_allowed_lateness";

  private HourlyWindows() {}

  /** The production watermark strategy: bounded out-of-orderness on event time, with idleness. */
  public static WatermarkStrategy<MeteredLine> watermarks() {
    return WatermarkStrategy.<MeteredLine>forBoundedOutOfOrderness(OUT_OF_ORDERNESS)
        .withTimestampAssigner((line, previous) -> line.eventTime().toEpochMilli())
        .withIdleness(Duration.ofMinutes(1));
  }

  /** How often an open hour emits its increment for the usage view (with 20 s checkpoints). */
  public static final Duration EARLY_FIRING = Duration.ofSeconds(20);

  /** Aggregates deduplicated lines into hourly increments. */
  public static SingleOutputStreamOperator<HourlyUsage> apply(
      DataStream<MeteredLine> lines, WatermarkStrategy<MeteredLine> watermarks) {
    return apply(lines, watermarks, null);
  }

  /**
   * Aggregates deduplicated lines into hourly increments, also firing every {@code earlyFiring} of
   * processing time while an hour is open (null: only at the end of the hour and for late lines).
   */
  public static SingleOutputStreamOperator<HourlyUsage> apply(
      DataStream<MeteredLine> lines,
      WatermarkStrategy<MeteredLine> watermarks,
      Duration earlyFiring) {
    WindowedStream<MeteredLine, String, TimeWindow> hours =
        lines
            .assignTimestampsAndWatermarks(watermarks)
            .name("event-time")
            .uid("event-time")
            .keyBy(l -> HourlyUsage.key(l.orgId(), l.workspaceId(), l.apiKeyId(), l.skuId()))
            .window(TumblingEventTimeWindows.of(Duration.ofHours(1)));
    if (earlyFiring != null) {
      hours = hours.trigger(new EarlyFiringTrigger(earlyFiring));
    }
    return hours
        .allowedLateness(ALLOWED_LATENESS)
        .sideOutputLateData(TOO_LATE)
        .aggregate(new Sum(), new EmitIncrement())
        .name("hourly-windows")
        .uid("hourly-windows");
  }

  /** A too-late line as a correction record (key = org ID). */
  public static UsageCorrection correction(MeteredLine line) {
    Instant t = line.eventTime();
    return UsageCorrection.newBuilder()
        .setRequestId(line.requestId())
        .setOrgId(line.orgId())
        .setSkuId(line.skuId())
        .setMeterCode(line.meter().code())
        .setQuantity(line.quantity())
        .setEventTime(Timestamp.newBuilder().setSeconds(t.getEpochSecond()).setNanos(t.getNano()))
        .setReason(LATE_REASON)
        .build();
  }

  /** Running (quantity, lines) of a window, in exact long arithmetic. */
  static final class Sum implements AggregateFunction<MeteredLine, long[], long[]> {
    private static final long serialVersionUID = 1L;

    @Override
    public long[] createAccumulator() {
      return new long[2];
    }

    @Override
    public long[] add(MeteredLine line, long[] acc) {
      acc[0] = Math.addExact(acc[0], line.quantity());
      acc[1] = Math.addExact(acc[1], 1);
      return acc;
    }

    @Override
    public long[] getResult(long[] acc) {
      return acc;
    }

    @Override
    public long[] merge(long[] a, long[] b) {
      return new long[] {Math.addExact(a[0], b[0]), Math.addExact(a[1], b[1])};
    }
  }

  /** Emits the window's total minus what earlier firings of the same window already emitted. */
  static final class EmitIncrement
      extends ProcessWindowFunction<long[], HourlyUsage, String, TimeWindow> {
    private static final long serialVersionUID = 1L;

    private static final ValueStateDescriptor<long[]> EMITTED =
        new ValueStateDescriptor<>("emitted", long[].class);

    @Override
    public void process(
        String key, Context ctx, Iterable<long[]> totals, Collector<HourlyUsage> out)
        throws Exception {
      long[] total = totals.iterator().next();
      ValueState<long[]> emittedState = ctx.windowState().getState(EMITTED);
      long[] emitted = emittedState.value();
      long quantity = Math.subtractExact(total[0], emitted == null ? 0 : emitted[0]);
      long lines = Math.subtractExact(total[1], emitted == null ? 0 : emitted[1]);
      if (lines == 0) {
        return;
      }
      String[] k = key.split("\u0000", -1);
      out.collect(
          new HourlyUsage(
              k[0],
              k[1],
              k[2],
              k[3],
              Instant.ofEpochMilli(ctx.window().getStart()),
              quantity,
              lines));
      emittedState.update(new long[] {total[0], total[1]});
    }

    @Override
    public void clear(Context ctx) {
      ctx.windowState().getState(EMITTED).clear();
    }
  }
}
