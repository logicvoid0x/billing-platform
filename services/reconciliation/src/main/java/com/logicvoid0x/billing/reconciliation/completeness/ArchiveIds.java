package com.logicvoid0x.billing.reconciliation.completeness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.CloseableIterable;

/**
 * Request IDs archived in {@code billing.usage_raw} for one event hour. Only {@code request_id} and
 * {@code event_time} are read, and the hour filter prunes to the hour's partition ({@code
 * hour(event_time)}). Duplicate archived copies collapse into the set.
 */
public final class ArchiveIds {
  private ArchiveIds() {}

  /** The archived request IDs of the hour that contains {@code hour}. */
  public static Set<String> forHour(Table archive, Instant hour) {
    Instant start = hour.truncatedTo(ChronoUnit.HOURS);
    long from = micros(start);
    long until = micros(start.plus(1, ChronoUnit.HOURS));
    Set<String> ids = new HashSet<>();
    try (CloseableIterable<Record> rows =
        IcebergGenerics.read(archive)
            .select("request_id", "event_time")
            .where(
                Expressions.and(
                    Expressions.greaterThanOrEqual("event_time", from),
                    Expressions.lessThan("event_time", until)))
            .build()) {
      for (Record r : rows) {
        ids.add((String) r.getField("request_id"));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("reading the raw archive failed", e);
    }
    return ids;
  }

  private static long micros(Instant t) {
    return Math.addExact(Math.multiplyExact(t.getEpochSecond(), 1_000_000L), t.getNano() / 1_000L);
  }
}
