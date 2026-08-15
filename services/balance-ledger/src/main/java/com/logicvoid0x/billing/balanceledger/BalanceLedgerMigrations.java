package com.logicvoid0x.billing.balanceledger;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Flyway migrations of the {@code balance_ledger} schema. */
public final class BalanceLedgerMigrations {
  /** The schema the balance ledger owns. */
  public static final String SCHEMA = "balance_ledger";

  private BalanceLedgerMigrations() {}

  /** Applies pending migrations; safe to run on every start. */
  public static void migrate(DataSource dataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .createSchemas(true)
        .locations("classpath:db/migration/balance_ledger")
        .load()
        .migrate();
  }
}
