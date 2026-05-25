package com.logicvoid0x.billing.domain;

/** Validation helpers for business identifiers. */
final class Ids {
  private Ids() {}

  /** Returns {@code value} if it is non-null and not blank. */
  static String require(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  /** Returns {@code value} if it is non-blank and has no ':' (the key separator). */
  static String requireSegment(String field, String value) {
    require(field, value);
    if (value.indexOf(':') >= 0) {
      throw new IllegalArgumentException(field + " must not contain ':'");
    }
    return value;
  }

  /** Returns {@code value} if it is not negative. */
  static long requireNonNegative(String field, long value) {
    if (value < 0) {
      throw new IllegalArgumentException(field + " must be >= 0");
    }
    return value;
  }
}
