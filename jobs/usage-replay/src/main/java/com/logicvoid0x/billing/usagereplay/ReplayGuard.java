package com.logicvoid0x.billing.usagereplay;

import com.logicvoid0x.billing.usageaggregator.HourlyWindows;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Refuses to replay hours the stream may still write (requirement: "refuse to replay hours inside
 * the allowed-lateness window"). An hour is sealed once the watermark has passed its end plus the
 * allowed lateness; the watermark trails event time by up to the out-of-orderness bound, so an hour
 * {@code h} is sealed at {@code h + 1 h + lateness + out-of-orderness}.
 */
public final class ReplayGuard {
  private ReplayGuard() {}

  /** The first instant at which {@code hour} is sealed. */
  public static Instant sealedAt(Instant hour) {
    return hour.plus(Duration.ofHours(1))
        .plus(HourlyWindows.ALLOWED_LATENESS)
        .plus(HourlyWindows.OUT_OF_ORDERNESS);
  }

  /**
   * Validates the hours to replay.
   *
   * @return the hours, sorted
   * @throws IllegalArgumentException if any hour is not hour-aligned or not yet sealed at {@code
   *     now}
   */
  public static SortedSet<Instant> requireSealed(Collection<Instant> hours, Instant now) {
    if (hours.isEmpty()) {
      throw new IllegalArgumentException("no hours to replay");
    }
    List<String> refused = new ArrayList<>();
    TreeSet<Instant> sorted = new TreeSet<>();
    for (Instant h : hours) {
      if (!h.truncatedTo(ChronoUnit.HOURS).equals(h)) {
        refused.add(h + " (not an hour boundary)");
      } else if (now.isBefore(sealedAt(h))) {
        refused.add(h + " (inside the allowed lateness until " + sealedAt(h) + ")");
      }
      sorted.add(h);
    }
    if (!refused.isEmpty()) {
      throw new IllegalArgumentException("refusing to replay " + String.join(", ", refused));
    }
    return sorted;
  }
}
