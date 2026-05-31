package com.logicvoid0x.billing.rating;

/** Validation helpers. */
final class Strings {
  private Strings() {}

  static String require(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
