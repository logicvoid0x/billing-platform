package com.logicvoid0x.billing.ratingengine;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Flyway migrations of the {@code rating} schema (rated_ledger). */
public final class RatingMigrations {
  /** The schema the rating engine owns. */
  public static final String SCHEMA = "rating";

  private RatingMigrations() {}

  /** Applies pending migrations; safe to run on every start. */
  public static void migrate(DataSource dataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .createSchemas(true)
        .locations("classpath:db/migration/rating")
        .load()
        .migrate();
  }
}
