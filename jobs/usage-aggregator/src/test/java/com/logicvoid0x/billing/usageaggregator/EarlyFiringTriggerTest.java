package com.logicvoid0x.billing.usageaggregator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.flink.api.common.state.State;
import org.apache.flink.api.common.state.StateDescriptor;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.streaming.api.windowing.triggers.Trigger;
import org.apache.flink.streaming.api.windowing.triggers.TriggerResult;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.junit.jupiter.api.Test;

/** An open hour fires every 20 s of processing time, so the usage view is under 60 s fresh. */
class EarlyFiringTriggerTest {
  private static final long HOUR = 3_600_000;
  private static final TimeWindow H10 = new TimeWindow(10 * HOUR, 11 * HOUR);

  /** Records timers; time and watermark are set by the test. */
  static final class Ctx implements Trigger.TriggerContext {
    long processingTime;
    long watermark = Long.MIN_VALUE;
    final List<Long> processingTimers = new ArrayList<>();
    final List<Long> eventTimers = new ArrayList<>();

    @Override
    public long getCurrentProcessingTime() {
      return processingTime;
    }

    @Override
    public MetricGroup getMetricGroup() {
      throw new UnsupportedOperationException();
    }

    @Override
    public long getCurrentWatermark() {
      return watermark;
    }

    @Override
    public void registerProcessingTimeTimer(long time) {
      processingTimers.add(time);
    }

    @Override
    public void registerEventTimeTimer(long time) {
      eventTimers.add(time);
    }

    @Override
    public void deleteProcessingTimeTimer(long time) {
      processingTimers.remove(Long.valueOf(time));
    }

    @Override
    public void deleteEventTimeTimer(long time) {
      eventTimers.remove(Long.valueOf(time));
    }

    @Override
    public <S extends State> S getPartitionedState(StateDescriptor<S, ?> stateDescriptor) {
      throw new UnsupportedOperationException();
    }
  }

  private final EarlyFiringTrigger trigger = new EarlyFiringTrigger(HourlyWindows.EARLY_FIRING);

  @Test
  void anOpenHourFiresAtTheNextTwentySecondBoundaryWithoutPurging() throws Exception {
    Ctx ctx = new Ctx();
    ctx.processingTime = 1_000_005_000L;
    ctx.watermark = 10 * HOUR + 60_000;

    assertThat(trigger.onElement("line", 10 * HOUR + 90_000, H10, ctx))
        .isEqualTo(TriggerResult.CONTINUE);
    assertThat(ctx.processingTimers).containsExactly(1_000_020_000L);
    assertThat(ctx.eventTimers).containsExactly(H10.maxTimestamp());

    long firesAt = ctx.processingTimers.get(0);
    assertThat(firesAt - ctx.processingTime)
        .as("a new request's usage is emitted within one interval")
        .isLessThanOrEqualTo(Duration.ofSeconds(20).toMillis());
    assertThat(trigger.onProcessingTime(firesAt, H10, ctx)).isEqualTo(TriggerResult.FIRE);
  }

  @Test
  void theHourStillFiresAtItsEndAndForLateLines() throws Exception {
    Ctx ctx = new Ctx();
    assertThat(trigger.onEventTime(H10.maxTimestamp(), H10, ctx)).isEqualTo(TriggerResult.FIRE);
    assertThat(trigger.onEventTime(H10.maxTimestamp() - 1, H10, ctx))
        .isEqualTo(TriggerResult.CONTINUE);

    ctx.watermark = H10.maxTimestamp() + 1;
    assertThat(trigger.onElement("late", 10 * HOUR + 5, H10, ctx)).isEqualTo(TriggerResult.FIRE);
    assertThat(trigger.onProcessingTime(ctx.processingTime, H10, ctx))
        .as("nothing early to add after the end")
        .isEqualTo(TriggerResult.CONTINUE);
  }

  @Test
  void clearRemovesTheEndOfHourTimer() throws Exception {
    Ctx ctx = new Ctx();
    trigger.onElement("line", 10 * HOUR, H10, ctx);
    trigger.clear(H10, ctx);
    assertThat(ctx.eventTimers).isEmpty();
  }
}
