package com.logicvoid0x.billing.usageaggregator.sink;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/** Flyway migrations of the {@code metering} schema (usage_hourly, applied_batches). */
public final class MeteringMigrations {
  /** The schema the aggregator owns. */
  public static final String SCHEMA = "metering";

  private MeteringMigrations() {}

  /** Applies pending migrations; safe to run on every start. */
  public static MigrateResult migrate(Jdbc jdbc) {
    return Flyway.configure()
        .dataSource(jdbc.url(), jdbc.user(), jdbc.password())
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .createSchemas(true)
        .locations("classpath:db/migration/metering")
        .load()
        .migrate();
  }
}
