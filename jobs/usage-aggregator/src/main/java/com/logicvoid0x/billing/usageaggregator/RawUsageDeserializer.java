package com.logicvoid0x.billing.usageaggregator;

import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/** Keeps each {@code usage.raw} record undecoded, with its position, for {@link ResolveUsage}. */
public final class RawUsageDeserializer implements KafkaRecordDeserializationSchema<RawUsage> {
  private static final long serialVersionUID = 1L;

  @Override
  public void deserialize(ConsumerRecord<byte[], byte[]> record, Collector<RawUsage> out) {
    byte[] value = record.value() == null ? new byte[0] : record.value();
    out.collect(new RawUsage(value, record.topic(), record.partition(), record.offset()));
  }

  @Override
  public TypeInformation<RawUsage> getProducedType() {
    return TypeInformation.of(RawUsage.class);
  }
}
