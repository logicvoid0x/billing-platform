package com.logicvoid0x.billing.domain.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BurnMeterTest {
  private static final long T0 = 1_793_000_000_000L; // epoch millis, a 10 s bucket boundary

  @Test
  void burnIsSpendPerSecondOverTheWindow() {
    BurnMeter m = new BurnMeter(60_000, 10_000);
    m.record(600, T0);
    m.record(600, T0 + 30_000);
    assertThat(m.perSecond(T0 + 30_000)).isEqualTo(20); // 1,200 cents over 60 s
  }

  @Test
  void spendAgesOutOfTheWindowBucketByBucket() {
    BurnMeter m = new BurnMeter(60_000, 10_000);
    m.record(600, T0);
    m.record(600, T0 + 30_000);
    assertThat(m.perSecond(T0 + 69_999)).isEqualTo(10); // first bucket has aged out
    assertThat(m.perSecond(T0 + 100_000)).isZero();
  }

  @Test
  void outOfOrderOrAncientRecordsAreHandled() {
    BurnMeter m = new BurnMeter(60_000, 10_000);
    m.record(100, T0 + 50_000);
    m.record(200, T0 + 10_000); // late but inside the window
    m.record(5_000, T0 - 600_000); // older than the window: ignored
    assertThat(m.perSecond(T0 + 50_000)).isEqualTo(5); // 300 / 60
  }

  @Test
  void configurationIsValidated() {
    assertThatThrownBy(() -> new BurnMeter(60_000, 7_000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BurnMeter(60_000, 10_000).record(-1, T0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
