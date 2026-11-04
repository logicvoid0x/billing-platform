package com.logicvoid0x.billing.reconciliation.completeness;

import com.logicvoid0x.billing.domain.RequestUsage;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One hourly completeness run over a sealed hour (requirement: "hourly on sealed hours ...
 * anti-join on requestId and re-emit missing usage with the original ID"). The gateway log is read
 * over a window wider than the hour, because records are produced after their event time, and
 * filtered on event time. Missing usage is re-emitted and awaited, so a later run of the same hour
 * finds it archived and reports zero drift.
 */
public final class CompletenessRun {
  /** Topic values over a Kafka timestamp range. */
  @FunctionalInterface
  public interface Fetch {
    /** Values of {@code topic} timestamped in [from, until). */
    List<byte[]> fetch(String topic, Instant from, Instant until);
  }

  /** Archived request IDs of an hour. */
  @FunctionalInterface
  public interface Archive {
    /** IDs archived for the hour that contains {@code hour}. */
    Set<String> ids(Instant hour);
  }

  /** Re-emits missing usage and waits for acknowledgement. */
  @FunctionalInterface
  public interface Sink {
    /** Re-emits {@code missing}. */
    void reemit(List<RequestUsage> missing) throws Exception;
  }

  /** Same rule as the rater and the replay guard: hour + 1 h + 1 h lateness + 5 min. */
  public static final Duration SEAL_DELAY = Duration.ofMinutes(125);

  private static final Duration BEFORE = Duration.ofMinutes(5);

  private final Fetch fetch;
  private final Archive archive;
  private final Sink sink;

  /** Creates the run. */
  public CompletenessRun(Fetch fetch, Archive archive, Sink sink) {
    this.fetch = Objects.requireNonNull(fetch, "fetch");
    this.archive = Objects.requireNonNull(archive, "archive");
    this.sink = Objects.requireNonNull(sink, "sink");
  }

  /**
   * Checks the hour that contains {@code hour} and re-emits what is missing.
   *
   * @throws IllegalArgumentException if the hour is not sealed at {@code now}
   */
  public CompletenessCheck.Result run(Instant hour, Instant now) throws Exception {
    Instant start = hour.truncatedTo(ChronoUnit.HOURS);
    if (now.isBefore(start.plus(SEAL_DELAY))) {
      throw new IllegalArgumentException(
          "hour " + start + " is not sealed until " + start.plus(SEAL_DELAY));
    }
    Instant from = start.minus(BEFORE);
    Instant until = start.plus(SEAL_DELAY);
    List<CompletenessCheck.GatewayRecord> gateway =
        GatewayLogReader.forHour(fetch.fetch("gateway.log", from, until), start);
    Set<String> accounted =
        new HashSet<>(AccountedIds.fromDeadLetters(fetch.fetch("usage.dlq", from, until)));
    // Corrections are produced for usage later than the lateness: they can arrive any time after.
    accounted.addAll(AccountedIds.fromCorrections(fetch.fetch("usage.corrections", from, now)));
    CompletenessCheck.Result result =
        CompletenessCheck.check(gateway, archive.ids(start), accounted);
    if (!result.reemitted().isEmpty()) {
      sink.reemit(result.reemitted());
    }
    return result;
  }
}
