package com.logicvoid0x.billing.reconciliation.completeness;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericFileWriterFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.InternalRecordWrapper;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.encryption.EncryptedFiles;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;

/** ArchiveIds over an in-memory table with the archive's schema and partitioning (Avro files). */
class ArchiveIdsTest {
  private static final Schema SCHEMA =
      new Schema(
          Types.NestedField.required(1, "request_id", Types.StringType.get()),
          Types.NestedField.required(2, "org_id", Types.StringType.get()),
          Types.NestedField.required(8, "event_time", Types.TimestampType.withZone()));
  private static final PartitionSpec SPEC =
      PartitionSpec.builderFor(SCHEMA).hour("event_time").build();
  private static final Instant H10 = Instant.parse("2026-11-01T10:00:00Z");

  @Test
  void returnsTheHoursRequestIdsOnce() throws Exception {
    InMemoryCatalog catalog = new InMemoryCatalog();
    catalog.initialize("archive-" + UUID.randomUUID(), Map.of());
    catalog.createNamespace(Namespace.of("billing"));
    Table t = catalog.createTable(TableIdentifier.of("billing", "usage_raw"), SCHEMA, SPEC);

    write(t, "req_a", H10.plusSeconds(10));
    write(t, "req_a", H10.plusSeconds(10)); // archived twice (at-least-once)
    write(t, "req_b", H10.plusSeconds(3_599));
    write(t, "req_next", H10.plusSeconds(3_600));
    write(t, "req_prev", H10.minusSeconds(1));

    assertThat(ArchiveIds.forHour(t, H10.plusSeconds(900)))
        .containsExactlyInAnyOrder("req_a", "req_b");
  }

  private static void write(Table t, String id, Instant at) throws Exception {
    // The table reassigns field IDs on creation: write with its own schema and spec.
    Schema schema = t.schema();
    PartitionSpec spec = t.spec();
    GenericRecord r = GenericRecord.create(schema);
    r.setField("request_id", id);
    r.setField("org_id", "org_1");
    r.setField("event_time", OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
    PartitionKey key = new PartitionKey(spec, schema);
    key.partition(new InternalRecordWrapper(schema.asStruct()).wrap(r));
    GenericFileWriterFactory factory =
        new GenericFileWriterFactory.Builder(t)
            .dataFileFormat(FileFormat.AVRO)
            .dataSchema(schema)
            .build();
    DataWriter<Record> w =
        factory.newDataWriter(
            EncryptedFiles.plainAsEncryptedOutput(
                t.io().newOutputFile(t.location() + "/data/" + UUID.randomUUID() + ".avro")),
            spec,
            key);
    w.write(r);
    w.close();
    t.newAppend().appendFile(w.toDataFile()).commit();
  }
}
