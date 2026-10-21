package com.logicvoid0x.billing.catalogclient;

import com.google.protobuf.InvalidProtocolBufferException;
import com.logicvoid0x.billing.contracts.v1.CatalogSnapshot;
import com.logicvoid0x.billing.rating.CatalogRatingLibrary;
import com.logicvoid0x.billing.rating.RatingCatalog;
import com.logicvoid0x.billing.rating.RatingLibrary;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reads the catalog from the catalog service's snapshot API (open question Q7): {@code GET
 * /v1/catalog/snapshot?hour=} for the rows of one hour, {@code GET /v1/catalog/version} for the
 * current catalog version.
 *
 * <p>Caches one {@link RatingLibrary} per hour, like the catalog service's own cache. The version
 * is checked at most once per {@code versionCheckEvery}; when it moved, every cached hour is
 * dropped, so a catalog write is visible within that interval. Failures throw {@link
 * CatalogUnavailableException} and are never cached.
 */
public final class HttpCatalogClient {
  /** Media type of the snapshot body. */
  public static final String PROTOBUF = "application/x-protobuf";

  private final URI base;
  private final Duration timeout;
  private final Duration versionCheckEvery;
  private final String codeVersion;
  private final int maxHours;
  private final Clock clock;
  private final HttpClient http;
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<Instant, RatingLibrary> byHour = new LinkedHashMap<>();

  private long knownVersion = -1;
  private Instant nextVersionCheck = Instant.MIN;

  /**
   * Creates the client.
   *
   * @param base the catalog service, e.g. {@code http://catalog:8080}
   * @param timeout per HTTP call
   * @param versionCheckEvery how stale a cached hour may be after a catalog write
   * @param codeVersion rating code version combined with the catalog version
   * @param maxHours most hours kept; the least recently used is dropped beyond it
   * @param clock decides when to re-check the version
   */
  public HttpCatalogClient(
      URI base,
      Duration timeout,
      Duration versionCheckEvery,
      String codeVersion,
      int maxHours,
      Clock clock) {
    this.base = Objects.requireNonNull(base, "base");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.versionCheckEvery = Objects.requireNonNull(versionCheckEvery, "versionCheckEvery");
    this.codeVersion = Objects.requireNonNull(codeVersion, "codeVersion");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (versionCheckEvery.isNegative() || maxHours < 1) {
      throw new IllegalArgumentException("versionCheckEvery must be >= 0 and maxHours >= 1");
    }
    this.maxHours = maxHours;
    this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  /**
   * The rating library for the hour that contains {@code at}.
   *
   * @throws CatalogUnavailableException if the catalog service fails
   */
  public RatingLibrary at(Instant at) {
    Instant hour = at.truncatedTo(ChronoUnit.HOURS);
    lock.lock();
    try {
      checkVersion();
      RatingLibrary cached = byHour.remove(hour);
      if (cached != null) {
        byHour.put(hour, cached); // most recently used goes last
        return cached;
      }
      RatingCatalog catalog = fetch(hour);
      long version = versionNumber(catalog.version());
      if (version != knownVersion) {
        // A write landed between the version check and the snapshot: older entries are stale.
        byHour.clear();
        knownVersion = version;
      }
      RatingLibrary library = new CatalogRatingLibrary(catalog, codeVersion);
      byHour.put(hour, library);
      if (byHour.size() > maxHours) {
        byHour.remove(byHour.keySet().iterator().next());
      }
      return library;
    } finally {
      lock.unlock();
    }
  }

  private void checkVersion() {
    Instant now = clock.instant();
    if (now.isBefore(nextVersionCheck)) {
      return;
    }
    String body = new String(get("/v1/catalog/version", "text/plain"), StandardCharsets.UTF_8);
    long version;
    try {
      version = Long.parseLong(body.trim());
    } catch (NumberFormatException e) {
      throw new CatalogUnavailableException("catalog version is not a number: " + body, e);
    }
    if (version != knownVersion) {
      byHour.clear();
      knownVersion = version;
    }
    nextVersionCheck = now.plus(versionCheckEvery);
  }

  private RatingCatalog fetch(Instant hour) {
    byte[] body = get("/v1/catalog/snapshot?hour=" + hour, PROTOBUF);
    try {
      CatalogSnapshot wire = CatalogSnapshot.parseFrom(body);
      if (!CatalogSnapshotCodec.hour(wire).equals(hour)) {
        throw new CatalogUnavailableException(
            "asked for hour " + hour + ", got " + CatalogSnapshotCodec.hour(wire));
      }
      return CatalogSnapshotCodec.decode(wire);
    } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
      throw new CatalogUnavailableException("catalog snapshot for " + hour + " is invalid", e);
    }
  }

  private byte[] get(String pathAndQuery, String accept) {
    HttpRequest request =
        HttpRequest.newBuilder(base.resolve(pathAndQuery))
            .timeout(timeout)
            .header("Accept", accept)
            .GET()
            .build();
    HttpResponse<byte[]> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new CatalogUnavailableException("GET " + pathAndQuery + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CatalogUnavailableException("GET " + pathAndQuery + " interrupted", e);
    }
    if (response.statusCode() != 200) {
      throw new CatalogUnavailableException(
          "GET " + pathAndQuery + " returned HTTP " + response.statusCode());
    }
    return response.body();
  }

  private static long versionNumber(String versionName) {
    if (!versionName.startsWith("catalog-v")) {
      throw new CatalogUnavailableException("not a catalog version: " + versionName);
    }
    try {
      return Long.parseLong(versionName.substring("catalog-v".length()));
    } catch (NumberFormatException e) {
      throw new CatalogUnavailableException("not a catalog version: " + versionName, e);
    }
  }
}
