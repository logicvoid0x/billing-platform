package com.logicvoid0x.billing.gateway;

import com.google.protobuf.Timestamp;
import com.logicvoid0x.billing.contracts.v1.GatewayLogEntry;
import com.logicvoid0x.billing.contracts.v1.SettleRecordEvent;
import com.logicvoid0x.billing.domain.SettleRecord;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Publishes settle records to {@code budget.settle} and customer-visible usage to {@code
 * gateway.log}, both keyed by request ID, with the same explicit delivery settings as the usage
 * emitter: acks=all, idempotence, unlimited retries in a 120 s delivery timeout. The design keeps
 * the gateway's record and the host's usage event as two independent references for completeness
 * reconciliation, so these go through their own producer.
 */
public final class KafkaGatewayEvents implements GatewayEvents, AutoCloseable {
  /** Settle records topic. */
  public static final String SETTLE_TOPIC = "budget.settle";

  /** Gateway log topic. */
  public static final String LOG_TOPIC = "gateway.log";

  private final Producer<String, byte[]> producer;
  private final AtomicLong failed = new AtomicLong();

  /** Creates the publisher over a producer configured with {@link #producerConfig}. */
  public KafkaGatewayEvents(Producer<String, byte[]> producer) {
    this.producer = Objects.requireNonNull(producer, "producer");
  }

  /** A publisher with its own producer. */
  public static KafkaGatewayEvents create(String bootstrapServers, String hostId) {
    return new KafkaGatewayEvents(
        new KafkaProducer<>(
            producerConfig(bootstrapServers, "gateway-events-" + hostId),
            new StringSerializer(),
            new ByteArraySerializer()));
  }

  /** The producer settings; every delivery guarantee is explicit. */
  public static Map<String, Object> producerConfig(String bootstrapServers, String clientId) {
    return Map.of(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
        bootstrapServers,
        ProducerConfig.CLIENT_ID_CONFIG,
        clientId,
        ProducerConfig.ACKS_CONFIG,
        "all",
        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
        true,
        ProducerConfig.RETRIES_CONFIG,
        Integer.MAX_VALUE,
        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
        120_000,
        ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION,
        5,
        ProducerConfig.LINGER_MS_CONFIG,
        5);
  }

  @Override
  public void settled(SettleRecord r) {
    SettleRecordEvent event =
        SettleRecordEvent.newBuilder()
            .setRequestId(r.requestId())
            .setOrgId(r.orgId())
            .setHomeRegion(r.homeRegion())
            .setEventTime(timestamp(r.eventTime()))
            .putAllHeldByBudget(r.heldByBudget())
            .putAllSettledByBudget(r.settledByBudget())
            .setFailOpen(r.failOpen())
            .setRatingLibraryVersion(r.ratingLibraryVersion())
            .setSequence(r.sequence())
            .build();
    send(SETTLE_TOPIC, r.requestId(), event.toByteArray());
  }

  @Override
  public void logged(LogEntry e) {
    GatewayLogEntry.Builder b =
        GatewayLogEntry.newBuilder()
            .setRequestId(e.requestId())
            .setOrgId(e.orgId())
            .setWorkspaceId(e.workspaceId())
            .setApiKeyId(e.apiKeyId())
            .setModel(e.model())
            .setEventTime(timestamp(e.eventTime()))
            .setSettledCents(e.settledCents())
            .setOutcome(e.outcome());
    e.quantities().forEach((m, q) -> b.putQuantities(m.code(), q));
    send(LOG_TOPIC, e.requestId(), b.build().toByteArray());
  }

  private void send(String topic, String key, byte[] value) {
    var unused =
        producer.send(
            new ProducerRecord<>(topic, key, value),
            (metadata, error) -> {
              if (error != null) {
                failed
                    .incrementAndGet(); // reconciliation catches a lost record (completeness loop)
              }
            });
  }

  /** Sends that failed after every retry. */
  public long failed() {
    return failed.get();
  }

  private static Timestamp timestamp(Instant t) {
    return Timestamp.newBuilder().setSeconds(t.getEpochSecond()).setNanos(t.getNano()).build();
  }

  @Override
  public void close() {
    producer.close(java.time.Duration.ofMinutes(2));
  }
}
