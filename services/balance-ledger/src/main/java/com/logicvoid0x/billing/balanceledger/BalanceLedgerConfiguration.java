package com.logicvoid0x.billing.balanceledger;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the ledger: migrations on start, then the ledger over the service's own schema. */
@Configuration(proxyBeanMethods = false)
public class BalanceLedgerConfiguration {
  /** The ledger, after the balance_ledger schema is migrated. */
  @Bean
  BalanceLedger balanceLedger(DataSource dataSource) {
    BalanceLedgerMigrations.migrate(dataSource);
    return new BalanceLedger(dataSource);
  }
}
