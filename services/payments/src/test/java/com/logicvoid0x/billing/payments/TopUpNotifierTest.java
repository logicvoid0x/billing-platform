package com.logicvoid0x.billing.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.contracts.v1.BudgetChange;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TopUpNotifierTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

  @Test
  void aPostedTopUpPublishesOneBudgetChangeAndReplaysPublishNothing() throws Exception {
    Map<String, Long> posted = new HashMap<>();
    List<String> keys = new ArrayList<>();
    List<BudgetChange> changes = new ArrayList<>();
    TopUpNotifier notifier =
        new TopUpNotifier(
            (key, org, cents, at) -> posted.putIfAbsent(key, cents) == null,
            (key, value) -> {
              keys.add(key);
              try {
                changes.add(BudgetChange.parseFrom(value));
              } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                throw new IllegalStateException(e);
              }
            });
    StripeSignature signature = new StripeSignature("whsec_t", Duration.ofMinutes(5));
    StripeWebhook webhook = new StripeWebhook(signature, notifier);
    String payload =
        "{\"type\":\"payment_intent.succeeded\",\"created\":"
            + NOW.getEpochSecond()
            + ",\"data\":{\"object\":{\"id\":\"pi_9\",\"amount_received\":500,\"currency\":\"usd\","
            + "\"metadata\":{\"org_id\":\"org_zero\"}}}}";
    String header = signature.header(payload, NOW.getEpochSecond());

    assertThat(webhook.handle(payload, header, NOW)).isEqualTo(StripeWebhook.Outcome.TOPPED_UP);
    assertThat(webhook.handle(payload, header, NOW)).isEqualTo(StripeWebhook.Outcome.DUPLICATE);

    assertThat(keys).containsExactly("org_zero:balance");
    assertThat(changes)
        .singleElement()
        .satisfies(c -> {
          assertThat(c.getCause()).isEqualTo("top_up");
          assertThat(c.getVersion()).isEqualTo(NOW.toEpochMilli());
          assertThat(c.getOrgId()).isEqualTo("org_zero");
        });
  }
}
