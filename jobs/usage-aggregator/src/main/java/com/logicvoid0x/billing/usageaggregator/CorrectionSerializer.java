package com.logicvoid0x.billing.usageaggregator;

import com.logicvoid0x.billing.domain.MeteredLine;
import java.nio.charset.StandardCharsets;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.kafka.clients.producer.ProducerRecord;

/** Too-late lines as {@code billing.v1.UsageCorrection} on {@code usage.corrections}, key = org. */
public final class CorrectionSerializer implements KafkaRecordSerializationSchema<MeteredLine> {
  private static final long serialVersionUID = 1L;

  private final String topic;

  /** Creates the serializer for {@code topic}. */
  public CorrectionSerializer(String topic) {
    this.topic = topic;
  }

  @Override
  public ProducerRecord<byte[], byte[]> serialize(
      MeteredLine line, KafkaSinkContext context, Long timestamp) {
    return new ProducerRecord<>(
        topic,
        line.orgId().getBytes(StandardCharsets.UTF_8),
        HourlyWindows.correction(line).toByteArray());
  }
}
