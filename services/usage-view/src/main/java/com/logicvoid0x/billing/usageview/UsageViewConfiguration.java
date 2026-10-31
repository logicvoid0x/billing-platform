package com.logicvoid0x.billing.usageview;

import com.logicvoid0x.billing.contracts.catalog.HttpCatalogClient;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the usage view: the metering rollup and the catalog for SKU models. */
@Configuration(proxyBeanMethods = false)
public class UsageViewConfiguration {
  /** Catalog snapshots from the catalog service (Q7); a month of hours cached. */
  @Bean
  HttpCatalogClient catalogClient(@Value("${usage-view.catalog-url}") String catalogUrl) {
    return new HttpCatalogClient(
        URI.create(catalogUrl),
        Duration.ofSeconds(2),
        Duration.ofSeconds(30),
        "rating-1",
        24 * 31,
        Clock.systemUTC());
  }

  /** Usage by key, with models from the catalog. */
  @Bean
  UsageByKey usageByKey(DataSource dataSource, HttpCatalogClient catalog) {
    return new UsageByKey(dataSource, catalog::catalogAt);
  }
}
