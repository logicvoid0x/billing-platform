package com.logicvoid0x.billing.usageconnect;

import com.google.protobuf.InvalidProtocolBufferException;
import com.logicvoid0x.billing.contracts.v1.RequestUsageEvent;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.data.Timestamp;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.storage.Converter;

/**
 * Connect value converter for {@code usage.raw}: protobuf {@code billing.v1.RequestUsageEvent}
 * bytes to a Connect struct, so the Iceberg sink can archive the raw facts as typed columns. Meter
 * codes stay map keys (permanent strings), quantities stay int64. Read-only: the archive never
 * writes back to Kafka.
 */
public final class UsageRawConverter implements Converter {
  /** The archived row schema, version 1. Column names match the protobuf fields. */
  public static final Schema SCHEMA =
      SchemaBuilder.struct()
          .name("billing.v1.RequestUsageEvent")
          .version(1)
          .field("request_id", Schema.STRING_SCHEMA)
          .field("org_id", Schema.STRING_SCHEMA)
          .field("workspace_id", Schema.STRING_SCHEMA)
          .field("api_key_id", Schema.STRING_SCHEMA)
          .field("model", Schema.STRING_SCHEMA)
          .field("service_tier", Schema.STRING_SCHEMA)
          .field("host_id", Schema.STRING_SCHEMA)
          .field("event_time", Timestamp.SCHEMA)
          .field("quantities", SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.INT64_SCHEMA).build())
          .build();

  @Override
  public void configure(Map<String, ?> configs, boolean isKey) {
    if (isKey) {
      throw new IllegalArgumentException("UsageRawConverter converts values only");
    }
  }

  @Override
  public byte[] fromConnectData(String topic, Schema schema, Object value) {
    throw new DataException("UsageRawConverter is read-only");
  }

  @Override
  @SuppressWarnings("JavaUtilDate") // the Connect Timestamp logical type is java.util.Date
  public SchemaAndValue toConnectData(String topic, byte[] value) {
    if (value == null) {
      return SchemaAndValue.NULL;
    }
    RequestUsageEvent e;
    try {
      e = RequestUsageEvent.parseFrom(value);
    } catch (InvalidProtocolBufferException ex) {
      throw new DataException("not a billing.v1.RequestUsageEvent on " + topic, ex);
    }
    long millis =
        Math.addExact(
            Math.multiplyExact(e.getEventTime().getSeconds(), 1000L),
            e.getEventTime().getNanos() / 1_000_000L);
    Struct row =
        new Struct(SCHEMA)
            .put("request_id", e.getRequestId())
            .put("org_id", e.getOrgId())
            .put("workspace_id", e.getWorkspaceId())
            .put("api_key_id", e.getApiKeyId())
            .put("model", e.getModel())
            .put("service_tier", e.getServiceTier())
            .put("host_id", e.getHostId())
            .put("event_time", new Date(millis))
            .put("quantities", new TreeMap<>(e.getQuantitiesMap()));
    return new SchemaAndValue(SCHEMA, row);
  }
}
