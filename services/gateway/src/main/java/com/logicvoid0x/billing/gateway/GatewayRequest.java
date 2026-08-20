package com.logicvoid0x.billing.gateway;

import com.logicvoid0x.billing.admission.BudgetRef;
import java.util.List;
import java.util.Objects;

/**
 * A customer request as the gateway sees it, after authentication resolved the org, workspace, API
 * key and the budgets that apply.
 *
 * @param orgId the org
 * @param workspaceId the workspace
 * @param apiKeyId the API key
 * @param model the model
 * @param serviceTier the service tier
 * @param inputTokens prompt tokens (input)
 * @param cacheReadTokens prompt tokens read from cache
 * @param cacheWriteTokens prompt tokens written to cache
 * @param maxOutputTokens the most output tokens the request may produce
 * @param budgets budgets that apply, from the budget registry
 * @param extraUsage whether money budgets back the request once allowances are used up
 * @param homeRegion the org's home region (for the settle record)
 * @param seed simulator seed (passed through to the inference host)
 */
public record GatewayRequest(
    String orgId,
    String workspaceId,
    String apiKeyId,
    String model,
    String serviceTier,
    long inputTokens,
    long cacheReadTokens,
    long cacheWriteTokens,
    long maxOutputTokens,
    List<BudgetRef> budgets,
    boolean extraUsage,
    String homeRegion,
    long seed) {
  /** Validates the request. */
  public GatewayRequest {
    Objects.requireNonNull(orgId, "orgId");
    Objects.requireNonNull(apiKeyId, "apiKeyId");
    Objects.requireNonNull(model, "model");
    budgets = List.copyOf(budgets);
    if (inputTokens < 0 || cacheReadTokens < 0 || cacheWriteTokens < 0 || maxOutputTokens < 1) {
      throw new IllegalArgumentException("token counts must be >= 0 and maxOutputTokens >= 1");
    }
  }
}
