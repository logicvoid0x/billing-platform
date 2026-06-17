package com.logicvoid0x.billing.domain.budget;

import static com.logicvoid0x.billing.domain.Mode.DENY;
import static com.logicvoid0x.billing.domain.Mode.DIRECT;
import static com.logicvoid0x.billing.domain.Mode.LEASE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ModePolicyTest {
  private final ModePolicy policy = ModePolicy.standard(10); // smallest hold 10 cents

  @Test
  void denyBelowTheSmallestHold() {
    assertThat(policy.next(LEASE, 9, 100)).isEqualTo(DENY);
    assertThat(policy.next(DIRECT, -50, 100)).isEqualTo(DENY);
    assertThat(policy.next(DENY, 10, 0)).isEqualTo(DIRECT);
  }

  @Test
  void idleAccountsUseDirect() {
    assertThat(policy.next(DIRECT, 1_000_000, 0)).isEqualTo(DIRECT);
    assertThat(policy.next(LEASE, 1_000_000, 0)).isEqualTo(DIRECT);
  }

  @Test
  void enterLeaseOnlyAboveOneHundredTwentySecondsOfRunway() {
    // burn 10 cents/s: 1,200 cents = exactly 120 s, not above it.
    assertThat(policy.next(DIRECT, 1_200, 10)).isEqualTo(DIRECT);
    assertThat(policy.next(DIRECT, 1_210, 10)).isEqualTo(LEASE);
    assertThat(policy.next(DENY, 5_000, 10)).isEqualTo(LEASE);
  }

  @Test
  void stayInLeaseUntilRunwayFallsBelowSixtySeconds() {
    assertThat(policy.next(LEASE, 900, 10)).isEqualTo(LEASE); // 90 s: no flapping
    assertThat(policy.next(LEASE, 600, 10)).isEqualTo(LEASE); // exactly 60 s
    assertThat(policy.next(LEASE, 590, 10)).isEqualTo(DIRECT); // 59 s
  }

  @Test
  void runwayUsesIntegerMathWithoutOverflow() {
    assertThat(policy.next(DIRECT, Long.MAX_VALUE, Long.MAX_VALUE / 200)).isEqualTo(LEASE);
    assertThat(ModePolicy.runwaySeconds(1_000, 3)).isEqualTo(333);
  }

  @Test
  void thresholdsMustBeOrdered() {
    assertThatThrownBy(() -> new ModePolicy(10, 120, 60))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
