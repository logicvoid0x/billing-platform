package com.logicvoid0x.billing.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PeriodCloseTest {
  private static final Instant OCT = Instant.parse("2026-10-01T00:00:00Z");
  private static final Instant NOV = Instant.parse("2026-11-01T00:00:00Z");
  private static final Instant DEC = Instant.parse("2026-12-01T00:00:00Z");

  @Test
  void aPeriodFreezesTwentyFourHoursAfterItEnds() {
    assertThat(PeriodClose.frozen(OCT, NOV.plusSeconds(86_399))).isFalse();
    assertThat(PeriodClose.frozen(OCT, NOV.plusSeconds(86_400))).isTrue();
    assertThat(PeriodClose.periodStart(Instant.parse("2026-10-31T23:59:59Z"))).isEqualTo(OCT);
  }

  @Test
  void correctionsStayInTheirPeriodUntilTheFreezeThenMoveToTheOpenOne() {
    assertThat(PeriodClose.place(OCT, -9_600, NOV.plusSeconds(3_600)))
        .isEqualTo(new PeriodClose.Placement(OCT, OCT, false, -9_600));
    assertThat(PeriodClose.place(OCT.plusSeconds(86_400 * 10), 500, NOV.plusSeconds(2 * 86_400)))
        .isEqualTo(new PeriodClose.Placement(OCT, NOV, true, 500));
    assertThat(PeriodClose.place(OCT, 7, DEC.plusSeconds(5)))
        .as("two periods back, November still in its 24 h: lands on the November invoice")
        .isEqualTo(new PeriodClose.Placement(OCT, NOV, true, 7));
    assertThat(PeriodClose.place(OCT, 7, DEC.plusSeconds(86_400)))
        .as("once November froze too: December")
        .isEqualTo(new PeriodClose.Placement(OCT, DEC, true, 7));
  }
}
