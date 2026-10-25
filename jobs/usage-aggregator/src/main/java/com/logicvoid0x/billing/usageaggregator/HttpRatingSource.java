package com.logicvoid0x.billing.usageaggregator;

import com.logicvoid0x.billing.contracts.catalog.HttpCatalogClient;
import com.logicvoid0x.billing.rating.RatingLibrary;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Rating libraries from the catalog service's snapshot API (Q7). Only the URL and code version are
 * shipped to Flink tasks; each task opens its own {@link HttpCatalogClient}, which caches per hour.
 */
public final class HttpRatingSource implements RatingSource {
  private static final long serialVersionUID = 1L;

  /** Hours cached per task: two days covers allowed lateness and replays of recent hours. */
  static final int MAX_HOURS = 48;

  private final String catalogUrl;
  private final String codeVersion;
  private transient HttpCatalogClient client;

  /**
   * Creates the source.
   *
   * @param catalogUrl base URL of the catalog service
   * @param codeVersion rating code version recorded with the catalog version
   */
  public HttpRatingSource(String catalogUrl, String codeVersion) {
    this.catalogUrl = Objects.requireNonNull(catalogUrl, "catalogUrl");
    this.codeVersion = Objects.requireNonNull(codeVersion, "codeVersion");
    URI.create(catalogUrl); // fail at submit time, not on the task
  }

  @Override
  public void open() {
    client =
        new HttpCatalogClient(
            URI.create(catalogUrl),
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            codeVersion,
            MAX_HOURS,
            Clock.systemUTC());
  }

  @Override
  public RatingLibrary at(Instant eventTime) {
    if (client == null) {
      open();
    }
    return client.at(eventTime);
  }

  @Override
  public String toString() {
    return "HttpRatingSource[" + catalogUrl + "]";
  }
}
