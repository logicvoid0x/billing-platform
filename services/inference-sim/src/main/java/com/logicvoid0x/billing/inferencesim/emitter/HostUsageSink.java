package com.logicvoid0x.billing.inferencesim.emitter;

import com.logicvoid0x.billing.domain.RequestUsage;
import java.util.Objects;

/**
 * The inference host's usage sink: counts the usage in the control totals, then hands it to the
 * usage sender (key = org ID). Never blocks inference.
 */
public final class HostUsageSink implements UsageSink {
  private final SpoolingSender usage;
  private final ControlTotalsRecorder controlTotals;

  /** Creates the sink. */
  public HostUsageSink(SpoolingSender usage, ControlTotalsRecorder controlTotals) {
    this.usage = Objects.requireNonNull(usage, "usage");
    this.controlTotals = Objects.requireNonNull(controlTotals, "controlTotals");
  }

  @Override
  public void emit(RequestUsage u) {
    controlTotals.record(u);
    usage.send(u.orgId(), UsageEventCodec.encode(u));
  }

  /** The record key of a spooled usage payload: its org ID. */
  public static String keyOf(byte[] payload) {
    return UsageEventCodec.decode(payload).orgId();
  }
}
