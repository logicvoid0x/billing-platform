package com.logicvoid0x.billing.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicvoid0x.billing.money.Cents.Rate;
import org.junit.jupiter.api.Test;

class CentsTest {
  private static final Rate FIFTEEN_PER_MILLION = new Rate(1500, 1_000_000);

  @Test
  void acmeGoldenExample() {
    // 40M output tokens at 20% off $15/M = 48,000 cents.
    Rate contract = FIFTEEN_PER_MILLION.discounted(2000);
    assertThat(contract).isEqualTo(new Rate(1200, 1_000_000));
    assertThat(Cents.forTotal(40_000_000, contract)).isEqualTo(48_000);
  }

  @Test
  void roundsHalfEvenOnce() {
    Rate halfCentPerUnit = new Rate(1, 2);
    assertThat(Cents.forTotal(1, halfCentPerUnit)).isZero(); // 0.5 -> 0
    assertThat(Cents.forTotal(3, halfCentPerUnit)).isEqualTo(2); // 1.5 -> 2
    assertThat(Cents.forTotal(5, halfCentPerUnit)).isEqualTo(2); // 2.5 -> 2
    assertThat(Cents.forTotal(7, halfCentPerUnit)).isEqualTo(4); // 3.5 -> 4
    assertThat(Cents.forTotal(2, new Rate(1, 3))).isOne(); // 0.67 -> 1
    assertThat(Cents.forTotal(1, new Rate(1, 3))).isZero(); // 0.33 -> 0
  }

  @Test
  void divideHalfEvenMatchesDefinition() {
    assertThat(Cents.divideHalfEven(0, 7)).isZero();
    assertThat(Cents.divideHalfEven(5, 2)).isEqualTo(2);
    assertThat(Cents.divideHalfEven(7, 2)).isEqualTo(4);
    assertThat(Cents.divideHalfEven(14, 4)).isEqualTo(4); // 3.5 -> 4
    assertThat(Cents.divideHalfEven(10, 4)).isEqualTo(2); // 2.5 -> 2
    assertThat(Cents.divideHalfEven(11, 4)).isEqualTo(3); // 2.75 -> 3
  }

  @Test
  void roundHalfEvenAddsARemainderOnceWithParityOfTheSum() {
    assertThat(Cents.roundHalfEven(1, 1, 2)).isEqualTo(2); // 1.5 -> 2
    assertThat(Cents.roundHalfEven(2, 1, 2)).isEqualTo(2); // 2.5 -> 2
    assertThat(Cents.roundHalfEven(0, 7, 2)).isEqualTo(4); // 3.5 -> 4
    assertThat(Cents.roundHalfEven(10, 0, 3)).isEqualTo(10);
    assertThatThrownBy(() -> Cents.roundHalfEven(0, -1, 2))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void zeroUnitsCostNothing() {
    assertThat(Cents.forTotal(0, FIFTEEN_PER_MILLION)).isZero();
    assertThat(Cents.charge(0, 0, FIFTEEN_PER_MILLION)).isZero();
  }

  @Test
  void smallRequestsAreNotLostWhenChargedOnRunningTotals() {
    // 1,000 requests of 100 tokens at $15/M: each is 0.15 cents, which per-request rounding bills
    // as 0.
    long total = 0;
    long charged = 0;
    for (int i = 0; i < 1_000; i++) {
      charged += Cents.charge(total, total + 100, FIFTEEN_PER_MILLION);
      total += 100;
    }
    assertThat(charged).isEqualTo(Cents.forTotal(total, FIFTEEN_PER_MILLION)).isEqualTo(150);
  }

  @Test
  void holdAddsOneCentForConcurrentMovement() {
    // 1,000 tokens at $15/M = 1.5 cents -> 2, plus 1.
    assertThat(Cents.hold(0, 1_000, FIFTEEN_PER_MILLION)).isEqualTo(3);
    assertThat(Cents.hold(0, 0, FIFTEEN_PER_MILLION)).isOne();
  }

  @Test
  void tenTrillionUnitsDoNotOverflow() {
    // $75 per million tokens, 10 trillion tokens = $750,000,000 = 75,000,000,000 cents.
    Rate rate = new Rate(7500, 1_000_000);
    assertThat(Cents.forTotal(10_000_000_000_000L, rate)).isEqualTo(75_000_000_000L);
    assertThat(Cents.forTotal(10_000_000_000_000L - 1, rate)).isEqualTo(75_000_000_000L);
  }

  @Test
  void overflowThrowsInsteadOfWrapping() {
    assertThatThrownBy(() -> Cents.forTotal(Long.MAX_VALUE, new Rate(Long.MAX_VALUE / 2, 1)))
        .isInstanceOf(ArithmeticException.class);
    assertThatThrownBy(() -> Cents.hold(Long.MAX_VALUE, 1, FIFTEEN_PER_MILLION))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void negativeUnitsAreRejected() {
    assertThatThrownBy(() -> Cents.forTotal(-1, FIFTEEN_PER_MILLION))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void invalidRatesAreRejected() {
    assertThatThrownBy(() -> new Rate(-1, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Rate(1, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThat(new Rate(0, 1).cents()).isZero();
  }

  @Test
  void fractionalDiscountIsRefused() {
    // 1 cent per unit at 20% off would be 0.8 cents: refuse, the SKU needs a larger perUnits.
    assertThatThrownBy(() -> new Rate(1, 1).discounted(2000))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fractional");
    assertThat(new Rate(10, 10).discounted(2000)).isEqualTo(new Rate(8, 10));
  }

  @Test
  void discountBoundsAreEnforced() {
    assertThat(FIFTEEN_PER_MILLION.discounted(0)).isEqualTo(FIFTEEN_PER_MILLION);
    assertThat(FIFTEEN_PER_MILLION.discounted(10_000)).isEqualTo(new Rate(0, 1_000_000));
    assertThatThrownBy(() -> FIFTEEN_PER_MILLION.discounted(-1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FIFTEEN_PER_MILLION.discounted(10_001))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void subCentPriceUsesLargerPerUnits() {
    // $0.075 per million = 75 cents per billion.
    Rate rate = new Rate(75, 1_000_000_000);
    assertThat(Cents.forTotal(1_000_000_000, rate)).isEqualTo(75);
    assertThat(Cents.forTotal(1_000_000, rate)).isZero(); // 0.075 cents
  }
}
