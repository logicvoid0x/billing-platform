package com.logicvoid0x.billing.inferencesim.emitter;

import com.logicvoid0x.billing.domain.RequestUsage;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Sends request usage to {@code usage.raw} (design seam 2: at-least-once delivery, deduplicated
 * downstream on {@code requestId:meterCode}).
 *
 * <p>Every delivery setting is set explicitly in code, so no client default or config file can
 * weaken it: {@code acks=all}, idempotence on (producer ID plus sequence, so retries are not
 * written twice), unlimited retries inside a 120 s delivery timeout, and at most 5 requests in
 * flight (the most idempotence keeps in order). The record key is the org ID, so one org's usage
 * stays on one partition in order.
 */
public final class UsageEmitter implements UsageSink, AutoCloseable {
  /** The raw usage topic. */
  public static final String TOPIC = "usage.raw";

  /** The delivery timeout the design requires. */
  public static final Duration DELIVERY_TIMEOUT = Duration.ofMinutes(2);

  private final Producer<String, byte[]> producer;
  private final String topic;
  private final BiConsumer<RequestUsage, Throwable> onFailure;
  private final AtomicLong delivered = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();

  /**
   * Creates the emitter over an existing producer.
   *
   * @param producer a producer configured with {@link #producerConfig}
   * @param topic the topic to write to
   * @param onFailure called with the usage of a send that failed after every retry (P4.3 spools it)
   */
  public UsageEmitter(
      Producer<String, byte[]> producer,
      String topic,
      BiConsumer<RequestUsage, Throwable> onFailure) {
    this.producer = Objects.requireNonNull(producer, "producer");
    this.topic = Objects.requireNonNull(topic, "topic");
    this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
  }

  /** An emitter with its own producer to {@link #TOPIC}. */
  public static UsageEmitter create(
      String bootstrapServers, String clientId, BiConsumer<RequestUsage, Throwable> onFailure) {
    return new UsageEmitter(
        new KafkaProducer<>(
            producerConfig(bootstrapServers, clientId),
            new StringSerializer(),
            new ByteArraySerializer()),
        TOPIC,
        onFailure);
  }

  /** The producer settings; every delivery guarantee is explicit here. */
  public static Map<String, Object> producerConfig(String bootstrapServers, String clientId) {
    return Map.ofEntries(
        Map.entry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers),
        Map.entry(ProducerConfig.CLIENT_ID_CONFIG, clientId),
        Map.entry(ProducerConfig.ACKS_CONFIG, "all"),
        Map.entry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true),
        Map.entry(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE),
        Map.entry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) DELIVERY_TIMEOUT.toMillis()),
        Map.entry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5),
        Map.entry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30_000),
        Map.entry(ProducerConfig.LINGER_MS_CONFIG, 5),
        Map.entry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000),
        Map.entry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()),
        Map.entry(
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName()));
  }

  /** Sends one request's usage, keyed by org ID; failure after every retry goes to onFailure. */
  @Override
  public void emit(RequestUsage usage) {
    // Failures are handled by onFailure inside send; the caller does not wait for the broker.
    var unused = send(usage);
  }

  /** Sends one request's usage; the future completes when the broker acknowledged it. */
  public CompletableFuture<RecordMetadata> send(RequestUsage usage) {
    CompletableFuture<RecordMetadata> result = new CompletableFuture<>();
    ProducerRecord<String, byte[]> record =
        new ProducerRecord<>(topic, usage.orgId(), UsageEventCodec.encode(usage));
    try {
      // The callback carries the outcome; the returned Future adds nothing.
      var unused =
          producer.send(
              record,
              (metadata, error) -> {
                if (error == null) {
                  delivered.incrementAndGet();
                  result.complete(metadata);
                } else {
                  fail(usage, error, result);
                }
              });
    } catch (RuntimeException e) {
      // Metadata wait exceeded or the producer is closed: the record never reached the buffer.
      fail(usage, e, result);
    }
    return result;
  }

  private void fail(RequestUsage usage, Throwable error, CompletableFuture<RecordMetadata> result) {
    failed.incrementAndGet();
    try {
      onFailure.accept(usage, error);
    } finally {
      result.completeExceptionally(error);
    }
  }

  /** Sends acknowledged by the broker. */
  public long delivered() {
    return delivered.get();
  }

  /** Sends that failed after every retry and went to onFailure. */
  public long failed() {
    return failed.get();
  }

  /** Waits for every buffered send to finish. */
  public void flush() {
    producer.flush();
  }

  @Override
  public void close() {
    producer.close(DELIVERY_TIMEOUT);
  }
}
