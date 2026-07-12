package com.logicvoid0x.billing.usageaggregator;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import com.logicvoid0x.billing.contracts.codec.UsageEventCodec;
import com.logicvoid0x.billing.contracts.v1.UsageDeadLetter;
import com.logicvoid0x.billing.domain.MeteredLine;
import com.logicvoid0x.billing.domain.RequestUsage;
import com.logicvoid0x.billing.rating.RatingException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

/**
 * First stage of the aggregator: decode and validate each raw event, then resolve each non-zero
 * meter to its SKU with the shared rating library. Anything that cannot be billed (malformed, an
 * invalid field, an unknown SKU) goes to the dead-letter side output as a {@code UsageDeadLetter}
 * with the original bytes and its source position: it is unbilled revenue, tracked, never dropped.
 */
public final class ResolveUsage extends ProcessFunction<RawUsage, MeteredLine> {
  private static final long serialVersionUID = 1L;

  /** Dead letters, serialized {@code billing.v1.UsageDeadLetter}. */
  public static final OutputTag<byte[]> DEAD_LETTERS = new OutputTag<>("usage-dlq") {};

  /** Dead-letter reason prefixes, stable for alerting. */
  public static final String MALFORMED = "malformed";

  /** The event decoded but a field is invalid. */
  public static final String INVALID = "invalid";

  /** No SKU (or no price data) for a meter of the event. */
  public static final String UNKNOWN_SKU = "unknown_sku";

  private final RatingSource rating;
  private transient Clock clock;

  /** Creates the function. */
  public ResolveUsage(RatingSource rating) {
    this.rating = Objects.requireNonNull(rating, "rating");
  }

  @Override
  public void open(OpenContext context) {
    rating.open();
    clock = Clock.systemUTC();
  }

  @Override
  public void processElement(RawUsage raw, Context ctx, Collector<MeteredLine> out) {
    RequestUsage usage;
    try {
      usage = UsageEventCodec.decode(raw.payload());
    } catch (IllegalArgumentException e) {
      // decode wraps parse errors; the domain constructor rejects invalid fields
      boolean parseError = e.getCause() instanceof InvalidProtocolBufferException;
      ctx.output(
          DEAD_LETTERS, deadLetter(raw, (parseError ? MALFORMED : INVALID) + ": " + message(e)));
      return;
    }
    List<MeteredLine> lines;
    try {
      lines = rating.at(usage.eventTime()).resolve(usage, usage.eventTime());
    } catch (RatingException e) {
      ctx.output(DEAD_LETTERS, deadLetter(raw, UNKNOWN_SKU + ": " + message(e)));
      return;
    }
    for (MeteredLine line : lines) {
      out.collect(line);
    }
  }

  private byte[] deadLetter(RawUsage raw, String reason) {
    Instant now = clock == null ? Instant.now() : clock.instant();
    return UsageDeadLetter.newBuilder()
        .setPayload(ByteString.copyFrom(raw.payload()))
        .setReason(reason)
        .setSourceTopic(raw.topic())
        .setSourcePartition(raw.partition())
        .setSourceOffset(raw.offset())
        .setFailedAt(
            Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()))
        .build()
        .toByteArray();
  }

  private static String message(Exception e) {
    String m = e.getMessage();
    return m == null ? e.getClass().getSimpleName() : m;
  }
}
