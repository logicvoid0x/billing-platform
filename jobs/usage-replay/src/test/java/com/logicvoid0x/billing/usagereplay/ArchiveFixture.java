package com.logicvoid0x.billing.usagereplay;

import com.logicvoid0x.billing.domain.RequestUsage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
import org.apache.iceberg.flink.TableLoader;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.types.Types;

/**
 * An in-memory {@code billing.usage_raw} with the archive's schema (UsageRawConverter) and
 * partitioning ({@code hour(event_time)}), written as Avro so no Hadoop file system is needed. The
 * catalog is static because the MiniCluster runs its tasks in this JVM.
 */
final class ArchiveFixture {
  static final Schema SCHEMA =
      new Schema(
          Types.NestedField.required(1, "request_id", Types.StringType.get()),
          Types.NestedField.required(2, "org_id", Types.StringType.get()),
          Types.NestedField.required(3, "workspace_id", Types.StringType.get()),
          Types.NestedField.required(4, "api_key_id", Types.StringType.get()),
          Types.NestedField.required(5, "model", Types.StringType.get()),
          Types.NestedField.required(6, "service_tier", Types.StringType.get()),
          Types.NestedField.required(7, "host_id", Types.StringType.get()),
          Types.NestedField.required(8, "event_time", Types.TimestampType.withZone()),
          Types.NestedField.required(
              9,
              "quantities",
              Types.MapType.ofRequired(10, 11, Types.StringType.get(), Types.LongType.get())));

  static final PartitionSpec SPEC = PartitionSpec.builderFor(SCHEMA).hour("event_time").build();
  static final TableIdentifier ID = TableIdentifier.of("billing", "usage_raw");

  private static InMemoryCatalog catalog;

  private ArchiveFixture() {}

  static synchronized Table create() {
    catalog = new InMemoryCatalog();
    catalog.initialize("archive-" + UUID.randomUUID(), Map.of());
    catalog.createNamespace(Namespace.of("billing"));
    return catalog.createTable(ID, SCHEMA, SPEC);
  }

  static synchronized Table table() {
    return catalog.loadTable(ID);
  }

  /** Appends one data file per call (like one connector commit). */
  static void append(List<RequestUsage> usages) {
    Table t = table();
    GenericFileWriterFactory factory =
        new GenericFileWriterFactory.Builder(t)
            .dataFileFormat(FileFormat.AVRO)
            .dataSchema(SCHEMA)
            .build();
    PartitionKey key = new PartitionKey(SPEC, SCHEMA);
    InternalRecordWrapper internal = new InternalRecordWrapper(SCHEMA.asStruct());
    Map<String, DataWriter<Record>> writers = new TreeMap<>();
    try {
      for (RequestUsage u : usages) {
        GenericRecord r = GenericRecord.create(SCHEMA);
        r.setField("request_id", u.requestId());
        r.setField("org_id", u.orgId());
        r.setField("workspace_id", u.workspaceId());
        r.setField("api_key_id", u.apiKeyId());
        r.setField("model", u.model());
        r.setField("service_tier", u.serviceTier());
        r.setField("host_id", u.hostId());
        r.setField("event_time", OffsetDateTime.ofInstant(u.eventTime(), ZoneOffset.UTC));
        Map<String, Long> q = new TreeMap<>();
        u.quantities().forEach((m, v) -> q.put(m.code(), v));
        r.setField("quantities", q);
        key.partition(internal.wrap(r)); // partition transforms read internal values (micros)
        String path = SPEC.partitionToPath(key);
        DataWriter<Record> w = writers.get(path);
        if (w == null) {
          w =
              factory.newDataWriter(
                  EncryptedFiles.plainAsEncryptedOutput(
                      t.io()
                          .newOutputFile(
                              t.location() + "/data/" + path + "/" + UUID.randomUUID() + ".avro")),
                  SPEC,
                  key.copy());
          writers.put(path, w);
        }
        w.write(r);
      }
      var append = t.newAppend();
      for (DataWriter<Record> w : writers.values()) {
        w.close();
        append.appendFile(w.toDataFile());
      }
      append.commit();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A loader Flink can ship to its tasks; resolves to the static in-memory table. */
  static final class Loader implements TableLoader {
    private static final long serialVersionUID = 1L;
    private transient boolean open;

    @Override
    public void open() {
      open = true;
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public Table loadTable() {
      return table();
    }

    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone") // stateless: a fresh loader is a full copy
    public TableLoader clone() {
      return new Loader();
    }

    @Override
    public void close() {
      open = false;
    }
  }
}
