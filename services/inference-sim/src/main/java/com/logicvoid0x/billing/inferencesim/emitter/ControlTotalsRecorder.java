package com.logicvoid0x.billing.inferencesim.emitter;

import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.RequestUsage;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Counts what this host emitted, per event hour: requests and the sum of each meter. The publisher
 * takes the counts accumulated since its last publish ({@link #takeDeltas}), so each control
 * message is a delta.
 */
public final class ControlTotalsRecorder {
  /**
   * Usage emitted for one event hour since the last take.
   *
   * @param requestCount requests emitted
   * @param quantityByMeter summed quantity per meter, ordered by meter code
   */
  public record Delta(long requestCount, SortedMap<MeterCode, Long> quantityByMeter) {
    /** Copies the sums. */
    public Delta {
      quantityByMeter = Collections.unmodifiableSortedMap(new TreeMap<>(quantityByMeter));
    }
  }

  private final ReentrantLock lock = new ReentrantLock();
  private TreeMap<Instant, Totals> byHour = new TreeMap<>();

  private static final class Totals {
    long requests;
    final TreeMap<MeterCode, Long> sums = new TreeMap<>();
  }

  /** Counts one emitted request. */
  public void record(RequestUsage usage) {
    lock.lock();
    try {
      Totals t = byHour.computeIfAbsent(usage.eventHour(), h -> new Totals());
      t.requests = Math.addExact(t.requests, 1);
      usage
          .quantities()
          .forEach((meter, quantity) -> t.sums.merge(meter, quantity, Math::addExact));
    } finally {
      lock.unlock();
    }
  }

  /** The counts since the last take, by event hour (ascending), and resets them. */
  public SortedMap<Instant, Delta> takeDeltas() {
    TreeMap<Instant, Totals> taken;
    lock.lock();
    try {
      taken = byHour;
      byHour = new TreeMap<>();
    } finally {
      lock.unlock();
    }
    TreeMap<Instant, Delta> out = new TreeMap<>();
    for (Map.Entry<Instant, Totals> e : taken.entrySet()) {
      out.put(e.getKey(), new Delta(e.getValue().requests, e.getValue().sums));
    }
    return Collections.unmodifiableSortedMap(out);
  }
}
