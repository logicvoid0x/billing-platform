package com.logicvoid0x.billing.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;

/** How a budget resets: never, a window anchored on first use, calendar periods, or rolling. */
public sealed interface Window
    permits Window.None, Window.Anchored, Window.Calendar, Window.Rolling {

  /** Permanent code of the window type. */
  String code();

  /**
   * Start of the window instance that contains {@code at}.
   *
   * @param at the time of use
   * @param currentStart start of the current instance; used only by anchored windows
   */
  Instant instanceStart(Instant at, Instant currentStart);

  /** When the instance containing {@code at} resets, or empty if it never does. */
  Optional<Instant> resetsAt(Instant at, Instant currentStart);

  /** A budget that never resets (a prepaid balance). */
  static Window none() {
    return None.INSTANCE;
  }

  /** A window of {@code length} that opens on first use, e.g. 5 hours. */
  static Window anchored(Duration length) {
    return new Anchored(length);
  }

  /** Calendar months in {@code zone}. */
  static Window calendarMonth(ZoneId zone) {
    return new Calendar(Calendar.Period.MONTH, zone);
  }

  /** Calendar days in {@code zone}. */
  static Window calendarDay(ZoneId zone) {
    return new Calendar(Calendar.Period.DAY, zone);
  }

  /** A rolling window of {@code length}, counted in {@code bucket}-sized buckets. */
  static Window rolling(Duration length, Duration bucket) {
    return new Rolling(length, bucket);
  }

  /** No reset. */
  enum None implements Window {
    /** The only instance. */
    INSTANCE;

    @Override
    public String code() {
      return "none";
    }

    @Override
    public Instant instanceStart(Instant at, Instant currentStart) {
      return Instant.EPOCH;
    }

    @Override
    public Optional<Instant> resetsAt(Instant at, Instant currentStart) {
      return Optional.empty();
    }
  }

  /**
   * Opens on first use and lasts {@code length}; the first use after it ends opens a new one.
   *
   * @param length window length, positive
   */
  record Anchored(Duration length) implements Window {
    /** Validates the window. */
    public Anchored {
      requirePositive("length", length);
    }

    @Override
    public String code() {
      return "anchored";
    }

    @Override
    public Instant instanceStart(Instant at, Instant currentStart) {
      return at.isBefore(currentStart.plus(length)) ? currentStart : at;
    }

    @Override
    public Optional<Instant> resetsAt(Instant at, Instant currentStart) {
      return Optional.of(instanceStart(at, currentStart).plus(length));
    }
  }

  /**
   * Calendar periods in a time zone.
   *
   * @param period day or month
   * @param zone zone whose midnight starts a period
   */
  record Calendar(Period period, ZoneId zone) implements Window {
    /** Calendar period lengths. */
    public enum Period {
      /** One calendar day. */
      DAY,
      /** One calendar month. */
      MONTH
    }

    /** Validates the window. */
    public Calendar {
      Objects.requireNonNull(period, "period");
      Objects.requireNonNull(zone, "zone");
    }

    @Override
    public String code() {
      return "calendar";
    }

    @Override
    public Instant instanceStart(Instant at, Instant currentStart) {
      return startOf(at).toInstant();
    }

    @Override
    public Optional<Instant> resetsAt(Instant at, Instant currentStart) {
      ZonedDateTime start = startOf(at);
      ZonedDateTime next = period == Period.DAY ? start.plusDays(1) : start.plusMonths(1);
      return Optional.of(next.toInstant());
    }

    private ZonedDateTime startOf(Instant at) {
      ZonedDateTime day = at.atZone(zone).toLocalDate().atStartOfDay(zone);
      return period == Period.DAY ? day : day.withDayOfMonth(1);
    }
  }

  /**
   * A rolling window of {@code length}, counted in epoch-aligned buckets of {@code bucket}; usage
   * ages out one bucket at a time.
   *
   * @param length window length, a positive multiple of {@code bucket}
   * @param bucket bucket size, positive
   */
  record Rolling(Duration length, Duration bucket) implements Window {
    /** Validates the window. */
    public Rolling {
      requirePositive("length", length);
      requirePositive("bucket", bucket);
      if (length.toMillis() % bucket.toMillis() != 0) {
        throw new IllegalArgumentException("bucket must divide length");
      }
    }

    @Override
    public String code() {
      return "rolling";
    }

    /** Start of the bucket that contains {@code at}. */
    @Override
    public Instant instanceStart(Instant at, Instant currentStart) {
      long size = bucket.toMillis();
      return Instant.ofEpochMilli(Math.floorDiv(at.toEpochMilli(), size) * size);
    }

    /** When the next bucket starts, which is when the oldest bucket ages out. */
    @Override
    public Optional<Instant> resetsAt(Instant at, Instant currentStart) {
      return Optional.of(instanceStart(at, currentStart).plus(bucket));
    }
  }

  private static void requirePositive(String field, Duration d) {
    if (d == null || d.isNegative() || d.isZero()) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }
}
