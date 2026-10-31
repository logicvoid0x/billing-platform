package com.logicvoid0x.billing.usageaggregator;

import java.time.Duration;
import org.apache.flink.streaming.api.windowing.triggers.Trigger;
import org.apache.flink.streaming.api.windowing.triggers.TriggerResult;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;

/**
 * Event-time trigger that also fires every {@code interval} of processing time while the window is
 * open, so the usage view sees an hour's usage before the hour ends (plan: "under 60 s fresh").
 * Firings never purge: {@link HourlyWindows}' increment function emits only what changed since the
 * previous firing, so early firings add up to the same hour totals. As with Flink's event-time
 * trigger, the window fires when the watermark passes its end and again for each late element.
 */
public final class EarlyFiringTrigger extends Trigger<Object, TimeWindow> {
  private static final long serialVersionUID = 1L;

  private final long intervalMillis;

  /** Fires every {@code interval} of processing time, aligned to multiples of it. */
  public EarlyFiringTrigger(Duration interval) {
    this.intervalMillis = interval.toMillis();
    if (intervalMillis <= 0) {
      throw new IllegalArgumentException("interval must be > 0");
    }
  }

  @Override
  public TriggerResult onElement(Object element, long timestamp, TimeWindow w, TriggerContext ctx)
      throws Exception {
    if (w.maxTimestamp() <= ctx.getCurrentWatermark()) {
      return TriggerResult.FIRE; // late element within the allowed lateness
    }
    ctx.registerEventTimeTimer(w.maxTimestamp());
    ctx.registerProcessingTimeTimer(nextBoundary(ctx.getCurrentProcessingTime()));
    return TriggerResult.CONTINUE;
  }

  @Override
  public TriggerResult onEventTime(long time, TimeWindow w, TriggerContext ctx) {
    return time == w.maxTimestamp() ? TriggerResult.FIRE : TriggerResult.CONTINUE;
  }

  @Override
  public TriggerResult onProcessingTime(long time, TimeWindow w, TriggerContext ctx) {
    // After the window's end the event-time firing has emitted everything; nothing to add early.
    return w.maxTimestamp() <= ctx.getCurrentWatermark()
        ? TriggerResult.CONTINUE
        : TriggerResult.FIRE;
  }

  @Override
  public void clear(TimeWindow w, TriggerContext ctx) {
    ctx.deleteEventTimeTimer(w.maxTimestamp());
  }

  private long nextBoundary(long now) {
    return Math.addExact(Math.floorDiv(now, intervalMillis) * intervalMillis, intervalMillis);
  }

  @Override
  public String toString() {
    return "EarlyFiringTrigger(" + intervalMillis + " ms)";
  }
}
