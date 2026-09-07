package com.logicvoid0x.billing.reconciliation.completeness;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;

/**
 * Reads every record of a topic whose Kafka timestamp falls in [from, until), partition by
 * partition, from the offset {@code offsetsForTimes} gives for {@code from} up to the first record
 * at or after {@code until} (or the end). Used for gateway.log, usage.dlq and usage.corrections;
 * callers pass a window wider than the event hour (records are produced a little after their event
 * time) and filter on event time themselves.
 */
public final class TopicRangeFetcher {
  private final Consumer<byte[], byte[]> consumer;
  private final Duration pollTimeout;

  /** Creates the fetcher over a consumer not subscribed to anything (it assigns partitions). */
  public TopicRangeFetcher(Consumer<byte[], byte[]> consumer, Duration pollTimeout) {
    this.consumer = Objects.requireNonNull(consumer, "consumer");
    this.pollTimeout = Objects.requireNonNull(pollTimeout, "pollTimeout");
  }

  /** The values of {@code topic} with timestamps in [from, until). */
  public List<byte[]> fetch(String topic, Instant from, Instant until) {
    List<TopicPartition> partitions = new ArrayList<>();
    for (PartitionInfo p : consumer.partitionsFor(topic)) {
      partitions.add(new TopicPartition(topic, p.partition()));
    }
    consumer.assign(partitions);
    Map<TopicPartition, Long> query = new HashMap<>();
    partitions.forEach(tp -> query.put(tp, from.toEpochMilli()));
    Map<TopicPartition, OffsetAndTimestamp> starts = consumer.offsetsForTimes(query);
    Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
    Map<TopicPartition, Long> stopAt = new HashMap<>();
    for (TopicPartition tp : partitions) {
      OffsetAndTimestamp start = starts.get(tp);
      long end = ends.getOrDefault(tp, 0L);
      if (start == null || start.offset() >= end) {
        continue; // nothing at or after `from`
      }
      consumer.seek(tp, start.offset());
      stopAt.put(tp, end);
    }
    consumer.pause(partitions.stream().filter(tp -> !stopAt.containsKey(tp)).toList());

    List<byte[]> out = new ArrayList<>();
    long untilMillis = until.toEpochMilli();
    while (!stopAt.isEmpty()) {
      for (ConsumerRecord<byte[], byte[]> r : consumer.poll(pollTimeout)) {
        TopicPartition tp = new TopicPartition(r.topic(), r.partition());
        Long end = stopAt.get(tp);
        if (end == null) {
          continue;
        }
        if (r.timestamp() >= untilMillis) {
          finish(tp, stopAt);
          continue;
        }
        out.add(r.value());
        if (r.offset() + 1 >= end) {
          finish(tp, stopAt);
        }
      }
    }
    return out;
  }

  private void finish(TopicPartition tp, Map<TopicPartition, Long> stopAt) {
    stopAt.remove(tp);
    consumer.pause(List.of(tp));
  }
}
