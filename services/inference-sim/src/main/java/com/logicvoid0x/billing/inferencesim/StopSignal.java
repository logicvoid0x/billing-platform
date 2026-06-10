package com.logicvoid0x.billing.inferencesim;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Asks a running stream to stop at the next chunk boundary, e.g. with reason {@code
 * budget_exhausted} from the gateway's stream guard. The first reason wins.
 */
public final class StopSignal {
  private final AtomicReference<String> reason = new AtomicReference<>();

  /** Requests a stop; later calls keep the first reason. */
  public void stop(String why) {
    reason.compareAndSet(null, why == null || why.isBlank() ? "stop_requested" : why);
  }

  /** Whether a stop was requested. */
  public boolean isStopped() {
    return reason.get() != null;
  }

  /** The stop reason, if stopped. */
  public Optional<String> reason() {
    return Optional.ofNullable(reason.get());
  }
}
