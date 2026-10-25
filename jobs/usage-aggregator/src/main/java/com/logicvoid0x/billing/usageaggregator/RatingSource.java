package com.logicvoid0x.billing.usageaggregator;

import com.logicvoid0x.billing.rating.RatingLibrary;
import java.io.Serializable;
import java.time.Instant;

/**
 * Where the job gets the rating library for an event hour. Serializable so Flink ships it to each
 * task; {@link #open} runs on the task before the first event. Deployed jobs use {@link
 * HttpRatingSource} (the catalog snapshot API, Q7); tests pass an in-memory catalog.
 */
public interface RatingSource extends Serializable {
  /** Prepares the source on the task (load or connect). */
  default void open() {}

  /** The rating library in effect at {@code eventTime}. */
  RatingLibrary at(Instant eventTime);
}
