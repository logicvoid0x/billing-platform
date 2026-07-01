package com.logicvoid0x.billing.catalog;

import com.logicvoid0x.billing.catalog.db.CatalogMigrations;
import com.logicvoid0x.billing.catalog.store.CatalogStore;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the catalog: migrations on start, the store, and the per-hour cache. */
@Configuration(proxyBeanMethods = false)
public class CatalogConfiguration {
  /** UTC wall clock for launch-gate defaults and cache version checks. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /** The store, after the catalog schema is migrated. */
  @Bean
  CatalogStore catalogStore(DataSource dataSource) {
    CatalogMigrations.migrate(dataSource);
    return new CatalogStore(dataSource);
  }

  /** The read API's cache. */
  @Bean
  CatalogCache catalogCache(
      CatalogStore store,
      Clock clock,
      @Value("${catalog.cache.version-check-every:30s}") Duration versionCheckEvery,
      @Value("${catalog.cache.max-hours:2048}") int maxHours,
      @Value("${catalog.rating-code-version:rating-1}") String codeVersion) {
    return new CatalogCache(store, clock, versionCheckEvery, codeVersion, maxHours);
  }
}
