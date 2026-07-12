package com.logicvoid0x.billing.inferencesim.emitter;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.logicvoid0x.billing.contracts.codec.UsageEventCodec;
import com.logicvoid0x.billing.contracts.v1.ControlTotals;
import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.RequestUsage;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Phase 4 "Done when": Kafka is killed mid-run and restarted. Every usage event arrives exactly
 * once after downstream dedupe on {@code requestId:meterCode}, and the control totals (deduplicated
 * on host + sequence, summed per hour) match what the host emitted.
 */
class KafkaOutageTest {
  private static final Instant H10 = Instant.parse("2026-11-01T10:00:00Z");
  private static final String HOST = "host-outage";

  private static int port;
  private static GenericContainer<?> kafka;

  @TempDir Path usageSpoolDir;
  @TempDir Path controlSpoolDir;

  @BeforeAll
  static void startKafka() throws IOException {
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    // A fixed host port, advertised as such, so the broker keeps its address across a restart.
    kafka =
        new GenericContainer<>("apache/kafka:4.3.2")
            .withEnv("KAFKA_NODE_ID", "1")
            .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
            .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,CONTROLLER://:9093")
            .withEnv("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://localhost:" + port)
            .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
            .withEnv(
                "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT")
            .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
            .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
            .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
            .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
            .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
            .withCreateContainerCmdModifier(cmd ->
                cmd.withExposedPorts(new ExposedPort(9092))
                    .getHostConfig()
                    .withPortBindings(
                        new PortBinding(Ports.Binding.bindPort(port), new ExposedPort(9092))))
            .waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2));
    kafka.start();
  }

  @AfterAll
  static void stopKafka() {
    if (kafka != null) {
      kafka.stop();
    }
  }

  private static String bootstrap() {
    return "localhost:" + port;
  }

  private static UsageEmitter emitter(String clientId, String topic) {
    return new UsageEmitter(
        new KafkaProducer<>(
            UsageEmitter.producerConfig(
                bootstrap(), clientId, Duration.ofSeconds(12), Duration.ofSeconds(5)),
            new StringSerializer(),
            new ByteArraySerializer()),
        topic);
  }

  private static RequestUsage usage(int i) {
    return new RequestUsage(
        "req_" + i,
        "org_" + (i % 4),
        "ws_1",
        "key_1",
        "model-x",
        "standard",
        HOST,
        H10.plusSeconds(60L * i), // spans two event hours
        Map.of(MeterCode.INPUT_TOKENS, 1_000L + i, MeterCode.OUTPUT_TOKENS, 10L + i));
  }

  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
  void everyEventArrivesExactlyOnceAfterDedupeAcrossAKafkaKillAndRestart() throws Exception {
    try (Admin admin =
        Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap()))) {
      admin
          .createTopics(
              List.of(
                  new NewTopic(UsageEmitter.TOPIC, 3, (short) 1),
                  new NewTopic(ControlTotalsPublisher.TOPIC, 1, (short) 1)))
          .all()
          .get(30, TimeUnit.SECONDS);
    }

    List<RequestUsage> emitted = new ArrayList<>();
    UsageSpool usageSpool = UsageSpool.open(usageSpoolDir);
    UsageSpool controlSpool = UsageSpool.open(controlSpoolDir);
    ControlTotalsRecorder recorder = new ControlTotalsRecorder();
    UsageEmitter usageEmitter = emitter("usage-outage", UsageEmitter.TOPIC);
    UsageEmitter controlEmitter = emitter("control-outage", ControlTotalsPublisher.TOPIC);
    try (SpoolingSender usageSender =
            new SpoolingSender(
                usageEmitter, usageSpool, HostUsageSink::keyOf, 50, Duration.ofSeconds(20));
        SpoolingSender controlSender =
            new SpoolingSender(
                controlEmitter,
                controlSpool,
                ControlTotalsPublisher::keyOf,
                50,
                Duration.ofSeconds(20))) {
      HostUsageSink sink = new HostUsageSink(usageSender, recorder);
      ControlTotalsPublisher publisher =
          new ControlTotalsPublisher(
              HOST, recorder, controlSender, Clock.systemUTC(), controlSpoolDir);

      // 1. Kafka up.
      for (int i = 0; i < 40; i++) {
        emitted.add(usage(i));
        sink.emit(usage(i));
      }
      publisher.publish();
      usageSender.awaitHandoff(Duration.ofSeconds(10));
      controlSender.awaitHandoff(Duration.ofSeconds(10));

      // 2. Kill the broker mid-run; keep producing. Sends fail after the delivery timeout and
      // spool.
      DockerClient docker = kafka.getDockerClient();
      docker.killContainerCmd(kafka.getContainerId()).exec();
      for (int i = 40; i < 80; i++) {
        emitted.add(usage(i));
        sink.emit(usage(i));
      }
      publisher.publish();
      await(
          () -> usageSender.spoolDepth() > 0 && controlSender.spoolDepth() > 0,
          90,
          () ->
              "usage spool "
                  + usageSender.spoolDepth()
                  + " (delivered "
                  + usageEmitter.delivered()
                  + ", failed "
                  + usageEmitter.failed()
                  + "), control spool "
                  + controlSender.spoolDepth()
                  + " (delivered "
                  + controlEmitter.delivered()
                  + ", failed "
                  + controlEmitter.failed()
                  + ")");
      assertThat(usageSender.drain()).as("broker still down").isZero();

      // 3. Restart it and drain both spools.
      docker.startContainerCmd(kafka.getContainerId()).exec();
      await(
          () -> {
            usageSender.drain();
            controlSender.drain();
            return usageSender.spoolDepth() == 0 && controlSender.spoolDepth() == 0;
          },
          120);

      // 4. Kafka back: usage goes straight through again.
      for (int i = 80; i < 100; i++) {
        emitted.add(usage(i));
        sink.emit(usage(i));
      }
      publisher.publish();
      usageSender.awaitHandoff(Duration.ofSeconds(10));
      controlSender.awaitHandoff(Duration.ofSeconds(10));
    } finally {
      usageSpool.close();
      controlSpool.close();
    }

    // Downstream dedupe on requestId:meterCode, as the aggregator does.
    Map<String, Long> deduped = new HashMap<>();
    for (byte[] value : consumeAll(UsageEmitter.TOPIC)) {
      RequestUsage u = UsageEventCodec.decode(value);
      u.quantities().forEach((m, q) -> deduped.putIfAbsent(u.dedupeKey(m), q));
    }
    Map<String, Long> expected = new HashMap<>();
    for (RequestUsage u : emitted) {
      u.quantities().forEach((m, q) -> expected.put(u.dedupeKey(m), q));
    }
    assertThat(deduped).isEqualTo(expected);

    // Control totals: dedupe on (host, sequence), sum per event hour; must equal the input.
    Set<Long> sequences = new HashSet<>();
    Map<Instant, Long> requestsByHour = new TreeMap<>();
    Map<String, Long> quantityByHourMeter = new TreeMap<>();
    for (byte[] value : consumeAll(ControlTotalsPublisher.TOPIC)) {
      ControlTotals c = ControlTotals.parseFrom(value);
      assertThat(c.getHostId()).isEqualTo(HOST);
      if (!sequences.add(c.getSequence())) {
        continue;
      }
      Instant hour = Instant.ofEpochSecond(c.getEventHour().getSeconds());
      requestsByHour.merge(hour, c.getRequestCount(), Long::sum);
      c.getQuantityByMeterMap()
          .forEach((m, q) -> quantityByHourMeter.merge(hour + "/" + m, q, Long::sum));
    }
    Map<Instant, Long> expectedRequests = new TreeMap<>();
    Map<String, Long> expectedQuantities = new TreeMap<>();
    for (RequestUsage u : emitted) {
      expectedRequests.merge(u.eventHour(), 1L, Long::sum);
      u.quantities()
          .forEach(
              (m, q) -> expectedQuantities.merge(u.eventHour() + "/" + m.code(), q, Long::sum));
    }
    assertThat(sequences)
        .as("no sequence gap")
        .containsExactlyInAnyOrderElementsOf(range(1, sequences.size()));
    assertThat(requestsByHour).isEqualTo(expectedRequests);
    assertThat(quantityByHourMeter).isEqualTo(expectedQuantities);
  }

  private static List<Long> range(long from, long to) {
    List<Long> out = new ArrayList<>();
    for (long i = from; i <= to; i++) {
      out.add(i);
    }
    return out;
  }

  private static List<byte[]> consumeAll(String topic) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "outage-check-" + topic);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
    List<byte[]> out = new ArrayList<>();
    try (KafkaConsumer<String, byte[]> consumer =
        new KafkaConsumer<>(props, new StringDeserializer(), new ByteArrayDeserializer())) {
      consumer.subscribe(List.of(topic));
      long quietSince = System.nanoTime();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (System.nanoTime() < deadline) {
        var records = consumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, byte[]> r : records) {
          out.add(r.value());
        }
        if (!records.isEmpty()) {
          quietSince = System.nanoTime();
        } else if (!out.isEmpty() && System.nanoTime() - quietSince > TimeUnit.SECONDS.toNanos(5)) {
          break;
        }
      }
    }
    return out;
  }

  private static void await(BooleanSupplier condition, int seconds) throws InterruptedException {
    await(condition, seconds, () -> "");
  }

  private static void await(
      BooleanSupplier condition, int seconds, java.util.function.Supplier<String> state)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within " + seconds + " s " + state.get());
      }
      TimeUnit.MILLISECONDS.sleep(500);
    }
  }
}
