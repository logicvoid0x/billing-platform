package com.logicvoid0x.billing.usageaggregator;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;

/**
 * One record read from {@code usage.raw}, still undecoded, with where it came from (so a dead
 * letter can point back at it).
 */
public final class RawUsage implements Serializable {
  private static final long serialVersionUID = 1L;

  private final byte[] payload;
  private final String topic;
  private final int partition;
  private final long offset;

  /** Creates the record. */
  public RawUsage(byte[] payload, String topic, int partition, long offset) {
    this.payload = Objects.requireNonNull(payload, "payload").clone();
    this.topic = Objects.requireNonNull(topic, "topic");
    this.partition = partition;
    this.offset = offset;
  }

  /** A copy of the record value. */
  public byte[] payload() {
    return payload.clone();
  }

  /** Source topic. */
  public String topic() {
    return topic;
  }

  /** Source partition. */
  public int partition() {
    return partition;
  }

  /** Source offset. */
  public long offset() {
    return offset;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof RawUsage r
        && Arrays.equals(payload, r.payload)
        && topic.equals(r.topic)
        && partition == r.partition
        && offset == r.offset;
  }

  @Override
  public int hashCode() {
    return Objects.hash(Arrays.hashCode(payload), topic, partition, offset);
  }

  @Override
  public String toString() {
    return topic + "-" + partition + "@" + offset;
  }
}
