package com.logicvoid0x.billing.inferencesim.emitter;

import com.logicvoid0x.billing.contracts.codec.UsageEventCodec;
import com.logicvoid0x.billing.domain.RequestUsage;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
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
 * stays on one partition in order. What happens to a send that fails after every retry is up to the
 * caller ({@link SpoolingUsageSink} spools it).
 */
public final class UsageEmitter implements AutoCloseable {
  /** The raw usage topic. */
  public static final String TOPIC = "usage.raw";

  /** The delivery timeout the design requires. */
  public static final Duration DELIVERY_TIMEOUT = Duration.ofMinutes(2);

  private final Producer<String, byte[]> producer;
  private final String topic;
  private final AtomicLong delivered = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();

  /**
   * Creates the emitter over an existing producer.
   *
   * @param producer a producer configured with {@link #producerConfig}
   * @param topic the topic to write to
   */
  public UsageEmitter(Producer<String, byte[]> producer, String topic) {
    this.producer = Objects.requireNonNull(producer, "producer");
    this.topic = Objects.requireNonNull(topic, "topic");
  }

  /** An emitter with its own producer to {@code topic}. */
  public static UsageEmitter create(String bootstrapServers, String clientId, String topic) {
    return new UsageEmitter(
        new KafkaProducer<>(
            producerConfig(bootstrapServers, clientId),
            new StringSerializer(),
            new ByteArraySerializer()),
        topic);
  }

  /** The producer settings; every delivery guarantee is explicit here. */
  public static Map<String, Object> producerConfig(String bootstrapServers, String clientId) {
    return producerConfig(bootstrapServers, clientId, DELIVERY_TIMEOUT, Duration.ofSeconds(30));
  }

  /**
   * Same settings with shorter timeouts, so tests reach the failure path in seconds. Production
   * code uses {@link #producerConfig(String, String)}.
   */
  static Map<String, Object> producerConfig(
      String bootstrapServers, String clientId, Duration deliveryTimeout, Duration requestTimeout) {
    return Map.ofEntries(
        Map.entry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers),
        Map.entry(ProducerConfig.CLIENT_ID_CONFIG, clientId),
        Map.entry(ProducerConfig.ACKS_CONFIG, "all"),
        Map.entry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true),
        Map.entry(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE),
        Map.entry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) deliveryTimeout.toMillis()),
        Map.entry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5),
        Map.entry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) requestTimeout.toMillis()),
        Map.entry(ProducerConfig.LINGER_MS_CONFIG, 5),
        Map.entry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000),
        Map.entry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()),
        Map.entry(
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName()));
  }

  /** Sends one request's usage keyed by org ID; completes when the broker acknowledged it. */
  public CompletableFuture<RecordMetadata> send(RequestUsage usage) {
    return send(usage.orgId(), UsageEventCodec.encode(usage));
  }

  /** Sends one encoded record; completes when the broker acknowledged it. */
  public CompletableFuture<RecordMetadata> send(String key, byte[] value) {
    CompletableFuture<RecordMetadata> result = new CompletableFuture<>();
    try {
      // The callback carries the outcome; the returned Future adds nothing.
      var unused =
          producer.send(
              new ProducerRecord<>(topic, key, value),
              (metadata, error) -> {
                if (error == null) {
                  delivered.incrementAndGet();
                  result.complete(metadata);
                } else {
                  failed.incrementAndGet();
                  result.completeExceptionally(error);
                }
              });
    } catch (RuntimeException e) {
      // Metadata wait exceeded or the producer is closed: the record never reached the buffer.
      failed.incrementAndGet();
      result.completeExceptionally(e);
    }
    return result;
  }

  /** Sends acknowledged by the broker. */
  public long delivered() {
    return delivered.get();
  }

  /** Sends that failed after every retry. */
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
