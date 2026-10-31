package com.logicvoid0x.billing.usagereplay;

import com.logicvoid0x.billing.contracts.codec.UsageEventCodec;
import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.RequestUsage;
import com.logicvoid0x.billing.usageaggregator.HourlyUsage;
import com.logicvoid0x.billing.usageaggregator.HttpRatingSource;
import com.logicvoid0x.billing.usageaggregator.RatingSource;
import com.logicvoid0x.billing.usageaggregator.RawUsage;
import com.logicvoid0x.billing.usageaggregator.UsageAggregatorJob;
import com.logicvoid0x.billing.usageaggregator.sink.Jdbc;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.SortedSet;
import java.util.TreeSet;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.data.ArrayData;
import org.apache.flink.table.data.MapData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.flink.CatalogLoader;
import org.apache.iceberg.flink.FlinkSchemaUtil;
import org.apache.iceberg.flink.TableLoader;
import org.apache.iceberg.flink.source.IcebergSource;
import org.apache.iceberg.flink.source.assigner.SimpleSplitAssignerFactory;

/**
 * Recomputes sealed hours of {@code usage_hourly} from the raw archive {@code billing.usage_raw}
 * (design: the archive is the source of truth for replay).
 *
 * <p>A Flink batch job reads the archived rows of the hours, resolves SKUs and dedupes on {@code
 * requestId:meterCode} with the same stages as the stream, and sums per (org, SKU, hour). {@link
 * RebuildSwap} then writes the result into {@code usage_hourly_rebuild} and swaps it in, in one
 * transaction. {@link ReplayGuard} refuses hours still inside the allowed lateness.
 */
public final class UsageReplayJob {
  private UsageReplayJob() {}

  /** Job name used when submitting to the cluster. */
  public static String jobName() {
    return "usage-replay";
  }

  /**
   * Replays {@code hours}.
   *
   * @return rows now in usage_hourly for those hours
   */
  public static int replay(
      StreamExecutionEnvironment env,
      TableLoader archive,
      SortedSet<Instant> requestedHours,
      Instant now,
      RatingSource rating,
      Jdbc metering,
      String replayId)
      throws Exception {
    SortedSet<Instant> hours = ReplayGuard.requireSealed(requestedHours, now);
    List<HourlyUsage> rows = recompute(env, archive, hours, rating);
    return RebuildSwap.replace(metering, replayId, hours, rows);
  }

  /** The hourly totals of {@code hours}, recomputed from the archive. */
  public static List<HourlyUsage> recompute(
      StreamExecutionEnvironment env,
      TableLoader archive,
      SortedSet<Instant> hours,
      RatingSource rating)
      throws Exception {
    env.setRuntimeMode(RuntimeExecutionMode.BATCH);
    // No defensive copies between chained operators: toRaw keeps no reference to the reader's
    // RowData, and copying Iceberg's rows otherwise falls back to Kryo.
    env.getConfig().enableObjectReuse();
    TreeSet<Instant> wanted = new TreeSet<>(hours);
    IcebergSource<RowData> source =
        IcebergSource.forRowData()
            .tableLoader(archive)
            .assignerFactory(new SimpleSplitAssignerFactory())
            .streaming(false)
            .filters(List.of(hourFilter(hours)))
            .build();
    DataStream<RawUsage> raw =
        env.fromSource(
                source,
                WatermarkStrategy.noWatermarks(),
                "usage_raw",
                TypeInformation.of(RowData.class))
            .map(UsageReplayJob::toRaw)
            .returns(TypeInformation.of(RawUsage.class))
            .filter(r -> wanted.contains(UsageEventCodec.decode(r.payload()).eventHour()))
            .name("archive-rows");
    DataStream<HourlyUsage> hourly =
        UsageAggregatorJob.dedupe(UsageAggregatorJob.resolve(raw, rating))
            .map(l ->
                new HourlyUsage(
                    l.orgId(),
                    l.workspaceId(),
                    l.apiKeyId(),
                    l.skuId(),
                    l.eventTime().truncatedTo(ChronoUnit.HOURS),
                    l.quantity(),
                    1))
            .returns(TypeInformation.of(HourlyUsage.class))
            .keyBy(HourlyUsage::key)
            .reduce(HourlyUsage::plus)
            .name("hourly-totals");
    List<HourlyUsage> out = new ArrayList<>();
    hourly.executeAndCollect(jobName()).forEachRemaining(out::add);
    return out;
  }

  /** Flink's row type of the archive table (avoids generic Kryo copies of RowData). */
  static InternalTypeInfo<RowData> rowType(TableLoader archive) {
    try (TableLoader loader = archive.clone()) {
      loader.open();
      return InternalTypeInfo.of(FlinkSchemaUtil.convert(loader.loadTable().schema()));
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  /** Partition pruning: event_time within any of the hours (rows are re-checked exactly). */
  static Expression hourFilter(SortedSet<Instant> hours) {
    long from = micros(hours.first());
    long until = micros(hours.last().plus(1, ChronoUnit.HOURS));
    return Expressions.and(
        Expressions.greaterThanOrEqual("event_time", from),
        Expressions.lessThan("event_time", until));
  }

  private static long micros(Instant t) {
    return Math.addExact(Math.multiplyExact(t.getEpochSecond(), 1_000_000L), t.getNano() / 1_000L);
  }

  /** An archived row (UsageRawConverter schema) back as the event bytes the stream saw. */
  static RawUsage toRaw(RowData row) {
    Map<MeterCode, Long> quantities = new LinkedHashMap<>();
    MapData map = row.getMap(8);
    ArrayData keys = map.keyArray();
    ArrayData values = map.valueArray();
    for (int i = 0; i < map.size(); i++) {
      quantities.put(new MeterCode(keys.getString(i).toString()), values.getLong(i));
    }
    RequestUsage usage =
        new RequestUsage(
            row.getString(0).toString(),
            row.getString(1).toString(),
            row.getString(2).toString(),
            row.getString(3).toString(),
            row.getString(4).toString(),
            row.getString(5).toString(),
            row.getString(6).toString(),
            row.getTimestamp(7, 6).toInstant(),
            quantities);
    return new RawUsage(UsageEventCodec.encode(usage), "billing.usage_raw", -1, -1);
  }

  /** The archive table written by the Iceberg sink connector. */
  public static final TableIdentifier ARCHIVE_TABLE = TableIdentifier.of("billing", "usage_raw");

  /**
   * Submits the job: {@code <replay-id> <catalog-url> <metering-jdbc-url> <db-user>
   * <iceberg-properties> <hour>...}. The password comes from {@link
   * UsageAggregatorJob#DB_PASSWORD_ENV}; the properties file configures the Iceberg REST catalog of
   * the archive (keys as in infra/replay/iceberg-local.properties). SKUs resolve through the
   * catalog snapshot API (Q7).
   */
  public static void main(String[] args) throws Exception {
    if (args.length < 6) {
      throw new IllegalArgumentException(
          "usage: UsageReplayJob <replay-id> <catalog-url> <metering-jdbc-url> <db-user>"
              + " <iceberg-properties> <hour>...  (password in "
              + UsageAggregatorJob.DB_PASSWORD_ENV
              + ")");
    }
    String password = System.getenv(UsageAggregatorJob.DB_PASSWORD_ENV);
    if (password == null) {
      throw new IllegalArgumentException(UsageAggregatorJob.DB_PASSWORD_ENV + " is not set");
    }
    SortedSet<Instant> hours = new TreeSet<>();
    for (int i = 5; i < args.length; i++) {
      hours.add(Instant.parse(args[i]));
    }
    TableLoader archive =
        TableLoader.fromCatalog(
            CatalogLoader.rest(
                "archive", new org.apache.hadoop.conf.Configuration(), icebergProperties(args[4])),
            ARCHIVE_TABLE);
    int rows =
        replay(
            StreamExecutionEnvironment.getExecutionEnvironment(),
            archive,
            hours,
            Instant.now(),
            new HttpRatingSource(args[1], "rating-1"),
            new Jdbc(args[2], args[3], password),
            args[0]);
    System.out.println("replay " + args[0] + ": " + rows + " usage_hourly rows for " + hours);
  }

  /** Iceberg catalog properties from a {@code .properties} file. */
  static Map<String, String> icebergProperties(String file) throws IOException {
    Properties p = new Properties();
    try (Reader in = Files.newBufferedReader(Path.of(file), StandardCharsets.UTF_8)) {
      p.load(in);
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (String key : p.stringPropertyNames()) {
      out.put(key, p.getProperty(key));
    }
    if (!out.containsKey("uri")) {
      throw new IllegalArgumentException(file + " has no Iceberg REST catalog 'uri'");
    }
    return out;
  }
}
