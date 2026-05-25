package com.logicvoid0x.billing.domain;

import java.util.regex.Pattern;

/**
 * A meter, identified by a permanent string code (never an enum name or ordinal). Codes appear in
 * dedupe keys ({@code requestId:meterCode}), SKUs and stored rows, so a code is never renamed or
 * reused.
 *
 * @param code lower snake case, starting with a letter, at most 64 characters
 */
public record MeterCode(String code) implements Comparable<MeterCode> {
  private static final Pattern VALID = Pattern.compile("[a-z][a-z0-9_]{0,63}");

  /** Uncached input tokens. */
  public static final MeterCode INPUT_TOKENS = new MeterCode("input_tokens");

  /** Output tokens. */
  public static final MeterCode OUTPUT_TOKENS = new MeterCode("output_tokens");

  /** Input tokens written to the prompt cache. */
  public static final MeterCode CACHE_WRITE_TOKENS = new MeterCode("cache_write_tokens");

  /** Input tokens read from the prompt cache. */
  public static final MeterCode CACHE_READ_TOKENS = new MeterCode("cache_read_tokens");

  /** Validates the code. */
  public MeterCode {
    if (code == null || !VALID.matcher(code).matches()) {
      throw new IllegalArgumentException("invalid meter code: " + code);
    }
  }

  /** Returns the meter with this code. */
  public static MeterCode of(String code) {
    return new MeterCode(code);
  }

  @Override
  public int compareTo(MeterCode other) {
    return code.compareTo(other.code);
  }

  @Override
  public String toString() {
    return code;
  }
}
