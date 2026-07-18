package com.logicvoid0x.billing.usageaggregator;

import com.logicvoid0x.billing.domain.MeteredLine;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.v2.DiscardingSink;

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

  /** Wires the job between Kafka topics. */
  public static void build(
      StreamExecutionEnvironment env, String bootstrapServers, RatingSource rating) {
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

    // Replaced by the checkpoint-aligned usage_hourly sink in P5.4.
    hourly.sinkTo(new DiscardingSink<>()).name("usage-hourly").uid("usage-hourly");
  }

  /** Submits the job: {@code <bootstrap-servers>}. */
  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("usage: UsageAggregatorJob <bootstrap-servers>");
    }
    // How the job reads the catalog is open question Q7; until then it cannot be submitted.
    throw new UnsupportedOperationException(
        "no catalog RatingSource for the deployed job yet (open question Q7)");
  }
}
