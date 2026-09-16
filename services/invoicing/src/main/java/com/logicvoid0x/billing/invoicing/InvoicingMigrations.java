package com.logicvoid0x.billing.invoicing;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Flyway migrations of the {@code invoicing} schema. */
public final class InvoicingMigrations {
  private InvoicingMigrations() {}

  /** Applies pending migrations; safe to run on every start. */
  public static void migrate(DataSource dataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas("invoicing")
        .defaultSchema("invoicing")
        .createSchemas(true)
        .locations("classpath:db/migration/invoicing")
        .load()
        .migrate();
  }
}
