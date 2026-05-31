package com.logicvoid0x.billing.rating;

/** Usage that cannot be resolved or priced: unknown SKU, no price or no weight in effect. */
public final class RatingException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public RatingException(String message) {
    super(message);
  }
}
