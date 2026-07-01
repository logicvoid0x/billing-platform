package com.logicvoid0x.billing.catalog;

import com.logicvoid0x.billing.catalog.store.CatalogStore;
import com.logicvoid0x.billing.rating.CatalogRatingLibrary;
import com.logicvoid0x.billing.rating.RatingCatalog;
import com.logicvoid0x.billing.rating.RatingLibrary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Read API of the catalog: a {@link RatingLibrary} for the hour that contains a given time, cached
 * per effective hour.
 *
 * <p>Each entry is a snapshot of the rows in effect during one hour. Every catalog write moves the
 * catalog version forward. The cache checks the version at most once per {@code versionCheckEvery}
 * and drops every entry when it changed, so a write is visible within that interval. Hours are
 * priced by one row each (prices are hour-aligned), so an hour's snapshot is all a caller needs.
 */
public final class CatalogCache {
  private final CatalogStore store;
  private final Clock clock;
  private final Duration versionCheckEvery;
  private final String codeVersion;
  private final int maxHours;
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<Instant, RatingLibrary> byHour;

  private long knownVersion = -1;
  private Instant nextVersionCheck = Instant.MIN;
  private long loads;

  /**
   * Creates the cache.
   *
   * @param store where the catalog lives
   * @param clock decides when to re-check the catalog version
   * @param versionCheckEvery how stale a cached hour may be after a catalog write
   * @param codeVersion rating code version recorded with every snapshot's version
   * @param maxHours most hours kept; the least recently used hour is dropped beyond it
   */
  public CatalogCache(
      CatalogStore store,
      Clock clock,
      Duration versionCheckEvery,
      String codeVersion,
      int maxHours) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.versionCheckEvery = Objects.requireNonNull(versionCheckEvery, "versionCheckEvery");
    this.codeVersion = Objects.requireNonNull(codeVersion, "codeVersion");
    if (versionCheckEvery.isNegative() || maxHours < 1) {
      throw new IllegalArgumentException("versionCheckEvery must be >= 0 and maxHours >= 1");
    }
    this.maxHours = maxHours;
    this.byHour = new LinkedHashMap<>();
  }

  /** The rating library for the hour that contains {@code at}. */
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
      RatingCatalog snapshot = store.snapshot(hour);
      loads++;
      RatingLibrary library = new CatalogRatingLibrary(snapshot, codeVersion);
      String expected = CatalogStore.versionName(knownVersion);
      if (!snapshot.version().equals(expected)) {
        // A write landed between the version check and the snapshot: the other entries are older.
        byHour.clear();
        knownVersion = CatalogStore.versionNumber(snapshot.version());
      }
      byHour.put(hour, library);
      if (byHour.size() > maxHours) {
        byHour.remove(byHour.keySet().iterator().next()); // least recently used
      }
      return library;
    } finally {
      lock.unlock();
    }
  }

  /** Snapshots loaded from the store so far (for tests and metrics). */
  public long loads() {
    lock.lock();
    try {
      return loads;
    } finally {
      lock.unlock();
    }
  }

  private void checkVersion() {
    Instant now = clock.instant();
    if (now.isBefore(nextVersionCheck)) {
      return;
    }
    long version = store.version();
    if (version != knownVersion) {
      byHour.clear();
      knownVersion = version;
    }
    nextVersionCheck = now.plus(versionCheckEvery);
  }
}
