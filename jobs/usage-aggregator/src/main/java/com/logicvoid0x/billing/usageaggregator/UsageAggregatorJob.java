package com.logicvoid0x.billing.usageaggregator;

import com.logicvoid0x.billing.domain.MeteredLine;
import com.logicvoid0x.billing.usageaggregator.sink.Jdbc;
import com.logicvoid0x.billing.usageaggregator.sink.UsageHourlySink;
import java.time.Duration;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.StateBackendOptions;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.core.execution.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/**
 * The Flink usage aggregator (design: "validate, resolve SKUs, dedupe, one batch per checkpoint").
 * Stages are added task by task: P5.1 source, validation, SKU resolution and the dead-letter queue;
 * P5.2 dedupe; P5.3 hourly windows; P5.4 the checkpoint-aligned batch sink.
 */
public final class UsageAggregatorJob {
  /** Raw usage topic. */
  public static final String SOURCE_TOPIC = "usage.raw";

  /** Dead-letter topic. */
  public static final String DLQ_TOPIC = "usage.dlq";

  /** Checkpoint interval (plan: 15-30 s). */
  public static final Duration CHECKPOINT_INTERVAL = Duration.ofSeconds(20);

  /** Lines later than the allowed lateness. */
  public static final String CORRECTIONS_TOPIC = "usage.corrections";

  private UsageAggregatorJob() {}

  /** Job name used when submitting to the cluster. */
  public static String jobName() {
    return "usage-aggregator";
  }

  /**
   * Decodes, validates and resolves raw usage. The returned stream carries metered lines; its side
   * output {@link ResolveUsage#DEAD_LETTERS} carries everything that could not be billed.
   */
  public static SingleOutputStreamOperator<MeteredLine> resolve(
      DataStream<RawUsage> raw, RatingSource rating) {
    return raw.process(new ResolveUsage(rating)).name("resolve-skus").uid("resolve-skus");
  }

  /** Drops repeated lines: the first line per {@code requestId:meterCode} wins (7-day TTL). */
  public static SingleOutputStreamOperator<MeteredLine> dedupe(DataStream<MeteredLine> lines) {
    return lines.keyBy(DedupeLines::key).process(new DedupeLines()).name("dedupe").uid("dedupe");
  }

  /** RocksDB state (plan: "RocksDB state backend"); dedupe keeps 7 days of keys. */
  public static Configuration stateConfig() {
    Configuration c = new Configuration();
    c.set(StateBackendOptions.STATE_BACKEND, "rocksdb");
    return c;
  }

  /** Wires the job between Kafka topics. */
  public static void build(
      StreamExecutionEnvironment env, String bootstrapServers, RatingSource rating, Jdbc metering) {
    // One usage_hourly batch per checkpoint (P5.4); exactly-once barriers, 20 s apart.
    env.enableCheckpointing(CHECKPOINT_INTERVAL.toMillis(), CheckpointingMode.EXACTLY_ONCE);
    env.configure(stateConfig());
    KafkaSource<RawUsage> source =
        KafkaSource.<RawUsage>builder()
            .setBootstrapServers(bootstrapServers)
            .setTopics(SOURCE_TOPIC)
            .setGroupId(jobName())
            // Only for a first start without a checkpoint; recovery restores checkpointed offsets.
            .setStartingOffsets(OffsetsInitializer.earliest())
            .setDeserializer(new RawUsageDeserializer())
            .build();
    // Event time is only known after decoding: watermarks are assigned on the metered lines.
    DataStream<RawUsage> raw =
        env.fromSource(source, WatermarkStrategy.noWatermarks(), "usage.raw").uid("usage-raw");
    SingleOutputStreamOperator<MeteredLine> lines = resolve(raw, rating);

    KafkaSink<byte[]> dlq =
        KafkaSink.<byte[]>builder()
            .setBootstrapServers(bootstrapServers)
            .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
            .setRecordSerializer(
                KafkaRecordSerializationSchema.<byte[]>builder()
                    .setTopic(DLQ_TOPIC)
                    .setValueSerializationSchema(bytes -> bytes)
                    .build())
            .build();
    lines.getSideOutput(ResolveUsage.DEAD_LETTERS).sinkTo(dlq).name("usage-dlq").uid("usage-dlq");

    SingleOutputStreamOperator<HourlyUsage> hourly =
        HourlyWindows.apply(dedupe(lines), HourlyWindows.watermarks());

    KafkaSink<MeteredLine> corrections =
        KafkaSink.<MeteredLine>builder()
            .setBootstrapServers(bootstrapServers)
            .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
            .setRecordSerializer(new CorrectionSerializer(CORRECTIONS_TOPIC))
            .build();
    hourly
        .getSideOutput(HourlyWindows.TOO_LATE)
        .sinkTo(corrections)
        .name("usage-corrections")
        .uid("usage-corrections");

    hourly
        .sinkTo(new UsageHourlySink(jobName(), metering))
        .name("usage-hourly")
        .uid("usage-hourly");
  }

  /** Environment variable holding the metering database password (kept out of the job args). */
  public static final String DB_PASSWORD_ENV = "METERING_DB_PASSWORD";

  /**
   * Submits the job: {@code <bootstrap-servers> <catalog-url> <metering-jdbc-url> <db-user>}; the
   * password comes from {@link #DB_PASSWORD_ENV}. SKUs resolve through the catalog snapshot API.
   */
  public static void main(String[] args) throws Exception {
    if (args.length != 4) {
      throw new IllegalArgumentException(
          "usage: UsageAggregatorJob <bootstrap-servers> <catalog-url> <metering-jdbc-url>"
              + " <db-user>  (password in "
              + DB_PASSWORD_ENV
              + ")");
    }
    String password = System.getenv(DB_PASSWORD_ENV);
    if (password == null) {
      throw new IllegalArgumentException(DB_PASSWORD_ENV + " is not set");
    }
    StreamExecutionEnvironment env =
        StreamExecutionEnvironment.getExecutionEnvironment(stateConfig());
    build(
        env,
        args[0],
        new HttpRatingSource(args[1], "rating-1"),
        new Jdbc(args[2], args[3], password));
    env.execute(jobName());
  }
}
