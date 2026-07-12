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
    // Event-time watermarks arrive with the hourly windows (P5.3).
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

    // Replaced by dedupe, windows and the batch sink in P5.2-P5.4.
    lines.sinkTo(new DiscardingSink<>()).name("metered-lines").uid("metered-lines");
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
