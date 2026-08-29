package com.logicvoid0x.billing.payments;

import com.google.protobuf.Timestamp;
import com.logicvoid0x.billing.contracts.v1.BudgetChange;
import java.time.Instant;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Wraps the top-up sink so every top-up that is actually posted publishes a {@code BudgetChange}
 * (cause {@code top_up}) to {@code budget.changes}, keyed by the org's balance budget (plan: "so
 * the home shard recomputes the mode; gateways drop cached DENY within 5 s"). A replayed top-up
 * posts nothing and so publishes nothing. The version is the payment's own time, in milliseconds,
 * so consumers apply changes in payment order and dedupe on (budget_id, version).
 */
public final class TopUpNotifier implements StripeWebhook.TopUps {
  /** The compacted budget-changes topic. */
  public static final String TOPIC = "budget.changes";

  private final StripeWebhook.TopUps inner;
  private final BiConsumer<String, byte[]> publish;

  /**
   * Creates the notifier.
   *
   * @param inner the ledger sink
   * @param publish sends (key, value) to {@link #TOPIC}
   */
  public TopUpNotifier(StripeWebhook.TopUps inner, BiConsumer<String, byte[]> publish) {
    this.inner = Objects.requireNonNull(inner, "inner");
    this.publish = Objects.requireNonNull(publish, "publish");
  }

  /** The balance budget of an org. */
  public static String balanceBudget(String orgId) {
    return orgId + ":balance";
  }

  @Override
  public boolean topUp(String key, String orgId, long cents, Instant at) {
    boolean posted = inner.topUp(key, orgId, cents, at);
    if (posted) {
      BudgetChange change =
          BudgetChange.newBuilder()
              .setBudgetId(balanceBudget(orgId))
              .setOrgId(orgId)
              .setKind("balance")
              .setCap(0)
              .setWindow("none")
              .setFailPolicy("closed")
              .setVersion(at.toEpochMilli())
              .setCause("top_up")
              .setChangedAt(
                  Timestamp.newBuilder().setSeconds(at.getEpochSecond()).setNanos(at.getNano()))
              .build();
      publish.accept(change.getBudgetId(), change.toByteArray());
    }
    return posted;
  }
}
