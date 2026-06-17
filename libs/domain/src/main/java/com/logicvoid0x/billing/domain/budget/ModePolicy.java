package com.logicvoid0x.billing.domain.budget;

import com.logicvoid0x.billing.domain.Mode;
import java.util.Objects;

/**
 * Admission mode of a budget from its runway (seconds of credit at the current burn), with
 * hysteresis so modes do not flap: {@code DENY} below the smallest possible hold; {@code DIRECT}
 * for idle accounts; {@code LEASE} is kept while runway stays at or above {@code
 * leaveLeaseBelowSeconds} (60 s) and entered only above {@code enterLeaseAboveSeconds} (120 s).
 *
 * @param minHold the smallest possible hold, in the budget's unit
 * @param leaveLeaseBelowSeconds a budget in {@code LEASE} drops to {@code DIRECT} below this runway
 * @param enterLeaseAboveSeconds a budget enters {@code LEASE} only above this runway
 */
public record ModePolicy(long minHold, long leaveLeaseBelowSeconds, long enterLeaseAboveSeconds) {
  /** Validates the thresholds. */
  public ModePolicy {
    if (minHold <= 0 || leaveLeaseBelowSeconds <= 0) {
      throw new IllegalArgumentException("minHold and thresholds must be > 0");
    }
    if (enterLeaseAboveSeconds < leaveLeaseBelowSeconds) {
      throw new IllegalArgumentException("enter threshold must be >= leave threshold");
    }
  }

  /** The design's thresholds: leave {@code LEASE} below 60 s, enter it above 120 s. */
  public static ModePolicy standard(long minHold) {
    return new ModePolicy(minHold, 60, 120);
  }

  /**
   * The next mode.
   *
   * @param current the budget's current mode
   * @param available spendable amount (may be negative after a bounded overdraft)
   * @param burnPerSecond current burn in the budget's unit per second, {@code >= 0}
   */
  public Mode next(Mode current, long available, long burnPerSecond) {
    Objects.requireNonNull(current, "current");
    if (available < minHold) {
      return Mode.DENY;
    }
    if (burnPerSecond <= 0) {
      return Mode.DIRECT;
    }
    long runway = runwaySeconds(available, burnPerSecond);
    if (current == Mode.LEASE) {
      return runway >= leaveLeaseBelowSeconds ? Mode.LEASE : Mode.DIRECT;
    }
    return runway > enterLeaseAboveSeconds ? Mode.LEASE : Mode.DIRECT;
  }

  /** Whole seconds of runway: {@code available / burnPerSecond}, for positive inputs. */
  public static long runwaySeconds(long available, long burnPerSecond) {
    return available / burnPerSecond;
  }
}
