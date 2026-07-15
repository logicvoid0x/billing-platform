package com.logicvoid0x.billing.usageaggregator;

import com.logicvoid0x.billing.domain.MeteredLine;
import java.time.Duration;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.metrics.Counter;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

/**
 * Stream dedupe (design seam 2: at-least-once delivery plus dedupe on {@code requestId:meterCode}).
 * Keyed by {@link #key}; the first line for a key passes, every later one is dropped. The state
 * keeps the first quantity, so a later line with a different quantity is counted as a conflict
 * (metric {@code dedupeConflicts}) instead of silently looking like a retry. State expires 7 days
 * after it was written; a duplicate later than that is the archive replay's job (Phase 6), not the
 * stream's.
 */
public final class DedupeLines extends KeyedProcessFunction<String, MeteredLine, MeteredLine> {
  private static final long serialVersionUID = 1L;

  /** How long a key is remembered. */
  public static final Duration TTL = Duration.ofDays(7);

  private transient ValueState<Long> firstQuantity;
  private transient Counter duplicates;
  private transient Counter conflicts;

  /** The dedupe key of a line: {@code requestId:meterCode}. */
  public static String key(MeteredLine line) {
    return line.requestId() + ":" + line.meter().code();
  }

  @Override
  public void open(OpenContext context) {
    ValueStateDescriptor<Long> descriptor =
        new ValueStateDescriptor<>("first-quantity", Long.class);
    descriptor.enableTimeToLive(
        StateTtlConfig.newBuilder(TTL)
            .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
            .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired)
            .build());
    firstQuantity = getRuntimeContext().getState(descriptor);
    duplicates = getRuntimeContext().getMetricGroup().counter("dedupeDuplicates");
    conflicts = getRuntimeContext().getMetricGroup().counter("dedupeConflicts");
  }

  @Override
  public void processElement(MeteredLine line, Context ctx, Collector<MeteredLine> out)
      throws Exception {
    Long seen = firstQuantity.value();
    if (seen == null) {
      firstQuantity.update(line.quantity());
      out.collect(line);
      return;
    }
    duplicates.inc();
    if (seen != line.quantity()) {
      conflicts.inc();
    }
  }
}
