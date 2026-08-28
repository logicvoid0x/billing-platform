package com.logicvoid0x.billing.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AutoRechargeTest {
  private static final Instant T = Instant.parse("2026-11-01T10:00:00Z");
  private static final AutoRecharge.Settings SETTINGS = new AutoRecharge.Settings(500, 2_000);

  private final List<String> charged = new ArrayList<>();
  private final List<CompletableFuture<Boolean>> pending = new ArrayList<>();
  private final Map<String, Long> posted = new HashMap<>();
  private final AtomicLong seq = new AtomicLong();

  private final AutoRecharge recharge =
      new AutoRecharge(
          (key, org, cents) -> {
            charged.add(key);
            CompletableFuture<Boolean> f = new CompletableFuture<>();
            pending.add(f);
            return f;
          },
          (key, org, cents, at) -> posted.putIfAbsent(key, cents) == null,
          org -> seq.incrementAndGet(),
          Duration.ofMinutes(10));

  @Test
  void aChargeStartsBelowTheThresholdOnlyOnceAtATime() {
    assertThat(recharge.check("org_1", 600, SETTINGS, T))
        .isEqualTo(AutoRecharge.Outcome.NOT_NEEDED);
    assertThat(recharge.check("org_1", 400, SETTINGS, T)).isEqualTo(AutoRecharge.Outcome.STARTED);
    assertThat(recharge.check("org_1", 300, SETTINGS, T)).isEqualTo(AutoRecharge.Outcome.IN_FLIGHT);
    assertThat(charged).containsExactly("org_1:recharge:1");

    pending.get(0).complete(true);
    assertThat(posted).containsExactly(Map.entry("org_1:recharge:1", 2_000L));
  }

  @Test
  void aFailedChargePostsNothingAndTheNextAttemptBacksOffWithANewSequence() {
    recharge.check("org_2", 0, SETTINGS, T);
    pending.get(0).complete(false); // declined
    assertThat(posted).isEmpty();
    assertThat(recharge.check("org_2", 0, SETTINGS, T.plusSeconds(60)))
        .isEqualTo(AutoRecharge.Outcome.BACKING_OFF);
    assertThat(recharge.check("org_2", 0, SETTINGS, T.plusSeconds(601)))
        .isEqualTo(AutoRecharge.Outcome.STARTED);
    assertThat(charged).containsExactly("org_2:recharge:1", "org_2:recharge:2");

    pending.get(1).completeExceptionally(new IllegalStateException("provider down"));
    assertThat(posted).as("an error is a failure too").isEmpty();
  }

  @Test
  void theSameKeyNamesTheChargeAndTheTopUpSoAReplayPostsOnce() {
    recharge.check("org_3", 0, SETTINGS, T);
    pending.get(0).complete(true);
    // The provider's success webhook for the same charge arrives later with the same key.
    StripeWebhook.TopUps ledger = (key, org, cents, at) -> posted.putIfAbsent(key, cents) == null;
    assertThat(ledger.topUp(AutoRecharge.key("org_3", 1), "org_3", 2_000, T)).isFalse();
    assertThat(posted).hasSize(1);
  }
}
