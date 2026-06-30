package com.logicvoid0x.billing.catalog.db;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Flyway migrations of the catalog service. The service owns the {@code catalog} schema (one schema
 * per service, see infra/postgres/init) and keeps its history table there.
 */
public final class CatalogMigrations {
  /** The schema the catalog service owns. */
  public static final String SCHEMA = "catalog";

  private CatalogMigrations() {}

  /** Flyway configured for the catalog schema on {@code dataSource}. */
  public static Flyway flyway(DataSource dataSource) {
    return Flyway.configure()
        .dataSource(dataSource)
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .createSchemas(true)
        .locations("classpath:db/migration/catalog")
        .load();
  }

  /** Applies pending migrations; safe to run on every start. */
  public static MigrateResult migrate(DataSource dataSource) {
    return flyway(dataSource).migrate();
  }
}
