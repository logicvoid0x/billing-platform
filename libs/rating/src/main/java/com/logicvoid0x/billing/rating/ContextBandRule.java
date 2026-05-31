package com.logicvoid0x.billing.rating;

import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.RequestUsage;
import java.util.List;
import java.util.Objects;

/**
 * Effective-dated context-band rule for a model: a request whose prompt (input, cache-write and
 * cache-read tokens) exceeds {@code thresholdTokens} is in {@code aboveBand}, otherwise in {@code
 * baseBand}. The band applies to every meter of the request. Which meters count is recorded in
 * docs/decisions/open-questions.md (Q2).
 *
 * @param model the model the rule applies to
 * @param thresholdTokens prompt size at or below which the base band applies
 * @param baseBand band at or below the threshold
 * @param aboveBand band above the threshold
 * @param effective when the rule applies
 */
public record ContextBandRule(
    String model,
    long thresholdTokens,
    String baseBand,
    String aboveBand,
    EffectivePeriod effective) {
  /** Meters that make up the prompt. */
  public static final List<MeterCode> PROMPT_METERS =
      List.of(MeterCode.INPUT_TOKENS, MeterCode.CACHE_WRITE_TOKENS, MeterCode.CACHE_READ_TOKENS);

  /** Validates the rule. */
  public ContextBandRule {
    Strings.require("model", model);
    Strings.require("baseBand", baseBand);
    Strings.require("aboveBand", aboveBand);
    Objects.requireNonNull(effective, "effective");
    if (thresholdTokens <= 0) {
      throw new IllegalArgumentException("thresholdTokens must be > 0");
    }
  }

  /** The band of {@code usage} under this rule. */
  public String bandOf(RequestUsage usage) {
    long prompt = 0;
    for (MeterCode m : PROMPT_METERS) {
      prompt = Math.addExact(prompt, usage.quantities().getOrDefault(m, 0L));
    }
    return prompt > thresholdTokens ? aboveBand : baseBand;
  }
}
