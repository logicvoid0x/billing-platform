package com.logicvoid0x.billing.inferencesim;

/**
 * A request to the simulated inference server. Output length is drawn deterministically from {@code
 * seed}, between 1 and {@code maxOutputTokens}.
 *
 * @param requestId minted by the gateway; usage is emitted under this ID
 * @param orgId org
 * @param workspaceId workspace
 * @param apiKeyId API key
 * @param model model
 * @param serviceTier service tier
 * @param inputTokens uncached input tokens
 * @param cacheReadTokens input tokens read from the prompt cache
 * @param cacheWriteTokens input tokens written to the prompt cache
 * @param maxOutputTokens upper bound on output tokens, {@code > 0}
 * @param seed makes the stream reproducible
 */
public record SimulatedRequest(
    String requestId,
    String orgId,
    String workspaceId,
    String apiKeyId,
    String model,
    String serviceTier,
    long inputTokens,
    long cacheReadTokens,
    long cacheWriteTokens,
    long maxOutputTokens,
    long seed) {
  /** Validates the request. */
  public SimulatedRequest {
    for (String s : new String[] {requestId, orgId, workspaceId, apiKeyId, model, serviceTier}) {
      if (s == null || s.isBlank()) {
        throw new IllegalArgumentException("ids, model and service tier must not be blank");
      }
    }
    if (inputTokens < 0 || cacheReadTokens < 0 || cacheWriteTokens < 0) {
      throw new IllegalArgumentException("token counts must be >= 0");
    }
    if (maxOutputTokens <= 0) {
      throw new IllegalArgumentException("maxOutputTokens must be > 0");
    }
  }
}
